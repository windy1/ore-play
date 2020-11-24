package controllers.apiv2

import java.nio.file.Path
import java.time.format.DateTimeParseException
import java.time.{LocalDate, OffsetDateTime}

import scala.jdk.CollectionConverters._

import play.api.http.HttpErrorHandler
import play.api.i18n.Lang
import play.api.inject.ApplicationLifecycle
import play.api.libs.Files
import play.api.mvc.{Action, AnyContent, MultipartFormData, Result}

import controllers.OreControllerComponents
import controllers.apiv2.helpers._
import controllers.sugar.Requests.ApiRequest
import db.impl.query.APIV2Queries
import models.protocols.APIV2
import models.querymodels.{APIV2QueryVersion, APIV2VersionStatsQuery}
import ore.OrePlatform
import ore.db.Model
import ore.db.access.ModelView
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.schema.{ProjectTable, VersionTable}
import ore.models.Job
import ore.models.project.Version.Stability
import ore.models.project._
import ore.models.project.factory.ProjectFactory
import ore.models.project.io.{PluginFileWithData, PluginUpload, VersionedPlatform}
import ore.models.user.{LoggedActionType, LoggedActionVersion, User}
import ore.permission.Permission
import ore.util.StringUtils.equalsIgnoreCase
import util.syntax._
import util.{PatchDecoder, UserActionLogger}

import _root_.io.circe._
import _root_.io.circe.derivation.annotations.SnakeCaseJsonCodec
import _root_.io.circe.syntax._
import cats.Applicative
import cats.data.{Const => _, _}
import cats.syntax.all._
import doobie.free.connection.ConnectionIO
import squeal.category._
import squeal.category.macros.Derive
import squeal.category.syntax.all._
import zio.blocking.Blocking
import zio.interop.catz._
import zio.{IO, UIO, ZIO}

class Versions(
    factory: ProjectFactory,
    val errorHandler: HttpErrorHandler,
    lifecycle: ApplicationLifecycle
)(
    implicit oreComponents: OreControllerComponents
) extends AbstractApiV2Controller(lifecycle) {

  import Versions._

  def listVersions(
      projectOwner: String,
      projectSlug: String,
      platforms: Seq[String],
      stability: Seq[Version.Stability],
      releaseType: Seq[Version.ReleaseType],
      limit: Option[Long],
      offset: Long
  ): Action[AnyContent] =
    CachingApiAction(Permission.ViewPublicInfo, APIScope.ProjectScope(projectOwner, projectSlug)).asyncF { request =>
      val realLimit  = limitOrDefault(limit, config.ore.projects.initVersionLoad.toLong)
      val realOffset = offsetOrZero(offset)
      val parsedPlatforms = platforms.map { s =>
        val splitted = s.split(":", 2)
        (splitted(0), splitted.lift(1))
      }.toList

      val getVersions = APIV2Queries
        .versionQuery(
          projectOwner,
          projectSlug,
          None,
          parsedPlatforms,
          stability.toList,
          releaseType.toList,
          request.globalPermissions.has(Permission.SeeHidden),
          request.user.map(_.id),
          realLimit,
          realOffset
        )
        .to[Vector]

      val countVersions = APIV2Queries
        .versionCountQuery(
          projectOwner,
          projectSlug,
          parsedPlatforms,
          stability.toList,
          releaseType.toList,
          request.globalPermissions.has(Permission.SeeHidden),
          request.user.map(_.id)
        )
        .unique

      (service.runDbCon(getVersions), service.runDbCon(countVersions)).parMapN { (versions, count) =>
        Ok(
          PaginatedVersionResult(
            Pagination(realLimit, realOffset, count),
            versions
          )
        )
      }
    }

  def showVersionAction(projectOwner: String, projectSlug: String, versionSlug: String): Action[AnyContent] =
    CachingApiAction(Permission.ViewPublicInfo, APIScope.ProjectScope(projectOwner, projectSlug)).asyncF {
      implicit request =>
        service
          .runDbCon(
            APIV2Queries
              .singleVersionQuery(
                projectOwner,
                projectSlug,
                versionSlug,
                request.globalPermissions.has(Permission.SeeHidden),
                request.user.map(_.id)
              )
              .option
          )
          .get
          .orElseFail(NotFound)
          .map(a => Ok(a.asJson))
    }

  def editVersion(projectOwner: String, projectSlug: String, versionSlug: String): Action[Json] =
    ApiAction(Permission.EditVersion, APIScope.ProjectScope(projectOwner, projectSlug)).asyncF(parseCirce.json) {
      implicit request =>
        val root = request.body.hcursor
        import cats.instances.list._

        def parsePlatforms(platforms: List[SimplePlatform]) = {
          platforms.distinct
            .traverse {
              case SimplePlatform(platformName, platformVersion) =>
                config.ore.platformsByName
                  .get(platformName)
                  .toValidNel(s"Don't know about the platform named $platformName")
                  .tupleRight(platformVersion)
            }
            .map { ps =>
              ps.traverse {
                case (platform, version) =>
                  OrePlatform
                    .produceVersionWarning(platform)(version)
                    .as(
                      VersionedPlatform(
                        platform.name,
                        version,
                        version.map(OrePlatform.coarseVersionOf(platform))
                      )
                    )

              }
            }
            .nested
            .value
        }

        //We take the platform as flat in the API, but want it columnar.
        //We also want to verify the version and platform name, and get a coarse version
        val res: ValidatedNel[String, Writer[List[String], (DbEditableVersion, Option[List[VersionedPlatform]])]] =
          EditableVersionF.patchDecoder
            .traverseKC(
              λ[PatchDecoder ~>: Compose2[Decoder.AccumulatingResult, Option, *]](_.decode(root))
            )
            .leftMap(_.map(_.show))
            .andThen { a =>
              a.platforms
                .traverse(parsePlatforms)
                .map(_.sequence)
                .tupleLeft(a)
            }
            .map {
              case (a, w) =>
                w.map { optPlatforms =>
                  val version = DbEditableVersionF[Option](
                    a.stability,
                    a.releaseType
                  )

                  version -> optPlatforms
                }
            }

        res match {
          case Validated.Valid(WriterT((warnings, (version, platforms)))) =>
            val versionIdQuery = for {
              p <- TableQuery[ProjectTable] if p.ownerName === projectOwner && equalsIgnoreCase(p.slug, projectSlug)
              v <- TableQuery[VersionTable]
              if p.id === v.projectId && equalsIgnoreCase(v.slug, versionSlug)
            } yield v.id

            service.runDBIO(versionIdQuery.result.head).flatMap { versionId =>
              val handlePlatforms = platforms.fold(ZIO.unit) { platforms =>
                val deleteAll = service.deleteWhere(PluginPlatform)(_.pluginId === versionId)
                val insertNew = service
                  .bulkInsert(platforms.map(p => PluginPlatform(versionId, p.id, p.version, p.coarseVersion)))
                  .unit

                deleteAll *> insertNew
              }

              val needEdit =
                version.foldLeftKC(false)(acc => Lambda[Option ~>: Const[Boolean]#λ](op => acc || op.isDefined))
              val doEdit =
                if (!needEdit) Applicative[ConnectionIO].unit
                else APIV2Queries.updateVersion(projectOwner, projectSlug, versionSlug, version).run.void

              handlePlatforms *> service
                .runDbCon(
                  //We need two queries as we use the generic update function
                  doEdit *> APIV2Queries
                    .singleVersionQuery(
                      projectOwner,
                      projectSlug,
                      versionSlug,
                      request.globalPermissions.has(Permission.SeeHidden),
                      request.user.map(_.id)
                    )
                    .unique
                )
                .map(r => Ok(WithAlerts(r, warnings = warnings)))
            }
          case Validated.Invalid(e) => ZIO.fail(BadRequest(ApiErrors(e)))
        }
    }

  def showVersionChangelog(projectOwner: String, projectSlug: String, versionSlug: String): Action[AnyContent] =
    CachingApiAction(Permission.ViewPublicInfo, APIScope.ProjectScope(projectOwner, projectSlug)).asyncF {
      service
        .runDBIO(
          TableQuery[ProjectTable]
            .join(TableQuery[VersionTable])
            .on(_.id === _.projectId)
            .filter { t =>
              t._1.ownerName === projectOwner &&
              equalsIgnoreCase(t._1.slug, projectSlug) &&
              equalsIgnoreCase(t._2.slug, versionSlug)
            }
            .map(_._2.description)
            .result
            .headOption
        )
        .map(_.fold(NotFound: Result)(a => Ok(APIV2.VersionChangelog(a))))
    }

  def updateChangelog(projectOwner: String, projectSlug: String, versionSlug: String): Action[APIV2.VersionChangelog] =
    ApiAction(Permission.EditVersion, APIScope.ProjectScope(projectOwner, projectSlug))
      .asyncF(parseCirce.decodeJson[APIV2.VersionChangelog]) { implicit request =>
        for {
          project <- projects.withSlug(projectOwner, projectSlug).someOrFail(NotFound)
          version <- project
            .versions(ModelView.now(Version))
            .find(equalsIgnoreCase(_.slug, versionSlug))
            .value
            .someOrFail(NotFound)
          oldDescription = version.description.getOrElse("")
          newDescription = request.body.changelog.trim
          _ <- if (newDescription.length < Page.maxLength) ZIO.unit
          else ZIO.fail(BadRequest(ApiError("Description too long")))
          _ <- service.update(version)(_.copy(description = Some(newDescription)))
          _ <- service.insert(Job.UpdateDiscourseVersionPost.newJob(version.id).toJob)
          _ <- UserActionLogger.logApi(
            request,
            LoggedActionType.VersionDescriptionEdited,
            version.id,
            newDescription,
            oldDescription
          )(LoggedActionVersion(_, Some(version.projectId)))
        } yield NoContent
      }

  def showVersionStats(
      projectOwner: String,
      projectSlug: String,
      version: String,
      fromDateString: String,
      toDateString: String
  ): Action[AnyContent] =
    CachingApiAction(Permission.IsProjectMember, APIScope.ProjectScope(projectOwner, projectSlug)).asyncF {
      import Ordering.Implicits._

      def parseDate(dateStr: String) =
        Validated
          .catchOnly[DateTimeParseException](LocalDate.parse(dateStr))
          .leftMap(_ => ApiErrors(NonEmptyList.one(s"Badly formatted date $dateStr")))

      for {
        t <- ZIO
          .fromEither(parseDate(fromDateString).product(parseDate(toDateString)).toEither)
          .mapError(BadRequest(_))
        (fromDate, toDate) = t
        _ <- ZIO.unit.filterOrFail(_ => fromDate < toDate)(BadRequest(ApiError("From date is after to date")))
        res <- service.runDbCon(
          APIV2Queries
            .versionStats(projectOwner, projectSlug, version, fromDate, toDate)
            .to[Vector]
            .map(APIV2VersionStatsQuery.asProtocol)
        )
      } yield Ok(res.asJson)
    }

  //TODO: Do the async part at some point
  private def readFileAsync(file: Path): ZIO[Blocking, Throwable, String] = {
    import zio.blocking._
    effectBlocking(java.nio.file.Files.readAllLines(file).asScala.mkString("\n"))
  }

  private def processVersionUploadToErrors(projectOwner: String, projectSlug: String)(
      implicit request: ApiRequest[MultipartFormData[Files.TemporaryFile]]
  ): ZIO[Blocking, Result, (Model[User], Model[Project], PluginFileWithData)] = {
    val fileF = ZIO.fromEither(
      request.body.file("plugin-file").toRight(BadRequest(ApiError("No plugin file specified")))
    )

    for {
      user    <- ZIO.fromOption(request.user).orElseFail(BadRequest(ApiError("No user found for session")))
      project <- projects.withSlug(projectOwner, projectSlug).get.orElseFail(NotFound)
      file    <- fileF
      pluginFile <- factory
        .collectErrorsForVersionUpload(PluginUpload(file.ref, file.filename), user, project)
        .leftMap { s =>
          implicit val lang: Lang = user.langOrDefault
          BadRequest(UserError(messagesApi(s)))
        }
    } yield (user, project, pluginFile)
  }

  def scanVersion(projectOwner: String, projectSlug: String): Action[MultipartFormData[Files.TemporaryFile]] =
    ApiAction(Permission.CreateVersion, APIScope.ProjectScope(projectOwner, projectSlug))(
      parse.multipartFormData(config.ore.projects.uploadMaxSize.toBytes)
    ).asyncF { implicit request =>
      for {
        t <- processVersionUploadToErrors(projectOwner, projectSlug)
        (user, _, pluginFile) = t
      } yield {
        val apiVersion = APIV2QueryVersion(
          OffsetDateTime.now(),
          "<placeholder>",
          "<placeholder>",
          Visibility.Public,
          0,
          Some(user.name),
          ReviewState.Unreviewed,
          Version.Stability.Stable,
          None,
          Nil,
          Nil,
          None
        )

        val warnings = NonEmptyList.fromList(pluginFile.warnings.toList)
        Ok(ScannedVersion(apiVersion.asProtocol, warnings))
      }
    }

  def deployVersion(projectOwner: String, projectSlug: String): Action[MultipartFormData[Files.TemporaryFile]] =
    ApiAction(Permission.CreateVersion, APIScope.ProjectScope(projectOwner, projectSlug))(
      parse.multipartFormData(config.ore.projects.uploadMaxSize.toBytes)
    ).asyncF { implicit request =>
      type TempFile = MultipartFormData.FilePart[Files.TemporaryFile]
      import zio.blocking._

      val pluginInfoFromFileF = ZIO.bracket(
        acquire = UIO(request.body.file("plugin-info")).get.mapError(Left.apply),
        release = (filePart: TempFile) => effectBlocking(java.nio.file.Files.deleteIfExists(filePart.ref)).fork,
        use = (filePart: TempFile) => readFileAsync(filePart.ref).mapError(Right.apply)
      )

      val dataStringF = ZIO
        .fromOption(request.body.dataParts.get("plugin-info").flatMap(_.headOption))
        .orElse(pluginInfoFromFileF)
        .catchAll {
          case Left(_)  => IO.fail("No plugin info specified")
          case Right(e) => IO.die(e)
        }

      val dataF = dataStringF
        .flatMap(s => ZIO.fromEither(parser.decode[DeployVersionInfo](s).leftMap(_.show)))
        .ensure("Description too long")(_.description.forall(_.length < Page.maxLength))
        .mapError(e => BadRequest(ApiError(e)))

      for {
        t <- processVersionUploadToErrors(projectOwner, projectSlug)
        (user, project, pluginFile) = t
        data <- dataF
        t <- factory
          .createVersion(
            project,
            data.versionName,
            pluginFile,
            data.description,
            data.createForumPost.getOrElse(project.settings.forumSync),
            data.stability.getOrElse(Stability.Stable),
            data.releaseType
          )
          .mapError { es =>
            implicit val lang: Lang = user.langOrDefault
            BadRequest(UserErrors(es.map(messagesApi(_))))
          }
      } yield {
        val (_, version) = t

        val apiVersion = APIV2QueryVersion(
          version.createdAt,
          version.name,
          version.slug,
          version.visibility,
          0,
          Some(user.name),
          version.reviewState,
          version.tags.stability,
          version.tags.releaseType,
          Nil,
          Nil,
          version.postId
        )

        Created(apiVersion.asProtocol)
      }
    }

  def hardDeleteVersion(projectOwner: String, projectSlug: String, versionSlug: String): Action[AnyContent] =
    ApiAction(Permission.HardDeleteVersion, APIScope.ProjectScope(projectOwner, projectSlug)).asyncF {
      implicit request =>
        projects
          .withSlug(projectOwner, projectSlug)
          .someOrFail(NotFound)
          .mproduct { p =>
            ModelView
              .now(Version)
              .find(v => v.projectId === p.id.value && equalsIgnoreCase(v.slug, versionSlug))
              .value
              .someOrFail(NotFound)
          }
          .flatMap {
            case (project, version) =>
              val log = UserActionLogger
                .logApi(
                  request,
                  LoggedActionType.VersionDeleted,
                  version.id,
                  "",
                  ""
                )(LoggedActionVersion(_, Some(project.id)))
                .unit

              log *> projects.deleteVersion(version).as(NoContent)
          }
    }

  def setVersionVisibility(projectOwner: String, projectSlug: String, versionSlug: String): Action[EditVisibility] =
    ApiAction(Permission.None, APIScope.ProjectScope(projectOwner, projectSlug))
      .asyncF(parseCirce.decodeJson[EditVisibility]) { implicit request =>
        projects
          .withSlug(projectOwner, projectSlug)
          .someOrFail(NotFound)
          .mproduct { p =>
            ModelView
              .now(Version)
              .find(v => v.projectId === p.id.value && equalsIgnoreCase(v.slug, versionSlug))
              .value
              .someOrFail(NotFound)
          }
          .flatMap {
            case (project, version) =>
              request.body.process(
                version,
                request.user.get.id,
                request.scopePermission,
                Permission.DeleteVersion,
                service.insert(Job.UpdateDiscourseVersionPost.newJob(version.id).toJob).unit,
                projects.deleteVersion(_: Model[Version]).unit,
                (newV, oldV) =>
                  UserActionLogger
                    .logApi(
                      request,
                      LoggedActionType.VersionDeleted,
                      version.id,
                      newV,
                      oldV
                    )(LoggedActionVersion(_, Some(project.id)))
                    .unit
              )
          }
      }

  def editDiscourseSettings(
      projectOwner: String,
      projectSlug: String,
      versionSlug: String
  ): Action[Versions.DiscourseModifyPostSettings] =
    ApiAction(Permission.EditAdminSettings, APIScope.ProjectScope(projectOwner, projectSlug))
      .asyncF(parseCirce.decodeJson[Versions.DiscourseModifyPostSettings]) { implicit request =>
        projects
          .withSlug(projectOwner, projectSlug)
          .someOrFail(NotFound)
          .flatMap { p =>
            ModelView
              .now(Version)
              .find(v => v.projectId === p.id.value && equalsIgnoreCase(v.slug, versionSlug))
              .value
              .someOrFail(NotFound)
          }
          .flatMap { version =>
            val update = service.update(version)(_.copy(postId = request.body.postId))
            val addJob = service.insert(Job.UpdateDiscourseVersionPost.newJob(version.id).toJob)

            update.as(NoContent) <* addJob.when(request.body.updatePost)
          }
      }
}
object Versions {

  //TODO: Allow setting multiple platforms
  @SnakeCaseJsonCodec case class DeployVersionInfo(
      versionName: String,
      createForumPost: Option[Boolean],
      description: Option[String],
      stability: Option[Version.Stability],
      releaseType: Option[Version.ReleaseType]
  )

  @SnakeCaseJsonCodec case class PaginatedVersionResult(
      pagination: Pagination,
      result: Seq[APIV2.Version]
  )

  @SnakeCaseJsonCodec case class SimplePlatform(
      platform: String,
      platformVersion: Option[String]
  )

  type EditableVersion   = EditableVersionF[Option]
  type DbEditableVersion = DbEditableVersionF[Option]
  case class EditableVersionF[F[_]](
      stability: F[Version.Stability],
      releaseType: F[Option[Version.ReleaseType]],
      platforms: F[List[SimplePlatform]]
  )
  object EditableVersionF {
    implicit val F
        : ApplicativeKC[EditableVersionF] with TraverseKC[EditableVersionF] with DistributiveKC[EditableVersionF] =
      Derive.allKC[EditableVersionF]

    val patchDecoder: EditableVersionF[PatchDecoder] =
      PatchDecoder.fromName(Derive.namesWithProductImplicitsC[EditableVersionF, Decoder])(
        _root_.io.circe.derivation.renaming.snakeCase
      )
  }

  case class DbEditableVersionF[F[_]](
      stability: F[Version.Stability],
      releaseType: F[Option[Version.ReleaseType]]
  )
  object DbEditableVersionF {
    implicit val F: ApplicativeKC[DbEditableVersionF]
      with TraverseKC[DbEditableVersionF]
      with DistributiveKC[DbEditableVersionF] = Derive.allKC[DbEditableVersionF]
  }

  @SnakeCaseJsonCodec case class ScannedVersion(
      version: APIV2.Version,
      warnings: Option[NonEmptyList[String]]
  )

  @SnakeCaseJsonCodec case class DiscourseModifyPostSettings(
      postId: Option[Int],
      updatePost: Boolean
  )
}
