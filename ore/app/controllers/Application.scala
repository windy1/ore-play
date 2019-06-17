package controllers

import java.sql.Timestamp
import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate}
import java.util.Date
import javax.inject.{Inject, Singleton}

import scala.util.Try

import play.api.mvc.{Action, ActionBuilder, AnyContent}
import play.api.routing.JavaScriptReverseRouter

import controllers.sugar.Requests.AuthRequest
import db.impl.query.AppQueries
import form.OreForms
import models.querymodels.{FlagActivity, ReviewActivity}
import models.viewhelper.{OrganizationData, UserData}
import ore.data.project.Category
import ore.data.{Platform, PlatformCategory}
import ore.db._
import ore.db.access.ModelView
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.schema.ProjectTableMain
import ore.markdown.MarkdownRenderer
import ore.member.MembershipDossier
import ore.models.organization.Organization
import ore.models.project.{ProjectSortingStrategy, _}
import ore.models.user._
import ore.models.user.role._
import ore.permission._
import ore.permission.role.{Role, RoleCategory}
import util.UserActionLogger
import util.syntax._
import views.{html => views}

import cats.Order
import cats.instances.vector._
import cats.syntax.all._
import zio.{IO, Task, UIO, ZIO}
import zio.interop.catz._

/**
  * Main entry point for application.
  */
@Singleton
final class Application @Inject()(forms: OreForms)(
    implicit oreComponents: OreControllerComponents,
    renderer: MarkdownRenderer
) extends OreBaseController {

  private def FlagAction = Authenticated.andThen(PermissionAction[AuthRequest](Permission.ModNotesAndFlags))

  def javascriptRoutes = Action { implicit request =>
    Ok(
      JavaScriptReverseRouter("jsRoutes")(
        controllers.project.routes.javascript.Projects.show,
        controllers.project.routes.javascript.Versions.show,
        controllers.routes.javascript.Users.showProjects
      )
    ).as("text/javascript")
  }

  /**
    * Show external link warning page.
    *
    * @return External link page
    */
  def linkOut(remoteUrl: String): Action[AnyContent] = OreAction { implicit request =>
    Ok(views.linkout(remoteUrl))
  }

  /**
    * Display the home page.
    *
    * @return Home page
    */
  def showHome(): Action[AnyContent] = OreAction { implicit request =>
    Ok(views.home())
  }

  /**
    * Shows the moderation queue for unreviewed versions.
    *
    * @return View of unreviewed versions.
    */
  def showQueue(): Action[AnyContent] =
    Authenticated.andThen(PermissionAction(Permission.Reviewer)).asyncF { implicit request =>
      // TODO: Pages
      service.runDbCon(AppQueries.getQueue.to[Vector]).map { queueEntries =>
        val (started, notStarted) = queueEntries.partitionEither(_.sort)
        Ok(views.users.admin.queue(started, notStarted))
      }
    }

  /**
    * Shows the overview page for flags.
    *
    * @return Flag overview
    */
  def showFlags(): Action[AnyContent] = FlagAction.asyncF { implicit request =>
    service
      .runDbCon(
        AppQueries
          .flags(request.user.id)
          .to[Vector]
      )
      .map(flagSeq => Ok(views.users.admin.flags(flagSeq)))
  }

  /**
    * Sets the resolved state of the specified flag.
    *
    * @param flagId   Flag to set
    * @param resolved Resolved state
    * @return         Ok
    */
  def setFlagResolved(flagId: DbRef[Flag], resolved: Boolean): Action[AnyContent] =
    FlagAction.asyncF { implicit request =>
      for {
        flag        <- ModelView.now(Flag).get(flagId).toZIOWithError(NotFound)
        user        <- users.current.value
        _           <- flag.markResolved(resolved, user)
        flagCreator <- flag.user[Task].orDie
        _ <- UserActionLogger.log(
          request,
          LoggedAction.ProjectFlagResolved,
          flag.projectId,
          s"Flag Resolved by ${user.fold("unknown")(_.name)}",
          s"Flagged by ${flagCreator.name}"
        )
      } yield Ok
    }

  def showHealth(): Action[AnyContent] =
    Authenticated.andThen(PermissionAction[AuthRequest](Permission.ViewHealth)).asyncF { implicit request =>
      implicit val timestampOrder: Order[Timestamp] = Order.from[Timestamp](_.compareTo(_))

      (
        service.runDbCon(AppQueries.getUnhealtyProjects(config.ore.projects.staleAge).to[Vector]),
        projects.missingFile.flatMap { versions =>
          versions.toVector.traverse(v => v.project[Task].orDie.tupleLeft(v))
        }
      ).parMapN { (unhealtyProjects, missingFileProjects) =>
        val noTopicProjects    = unhealtyProjects.filter(p => p.topicId.isEmpty || p.postId.isEmpty)
        val topicDirtyProjects = unhealtyProjects.filter(_.isTopicDirty)
        val staleProjects = unhealtyProjects
          .filter(_.lastUpdated > new Timestamp(new Date().getTime - config.ore.projects.staleAge.toMillis))
        val notPublic = unhealtyProjects.filter(_.visibility != Visibility.Public)
        Ok(
          views.users.admin.health(
            noTopicProjects,
            topicDirtyProjects,
            staleProjects,
            notPublic,
            Model.unwrapNested(missingFileProjects)
          )
        )
      }
    }

  /**
    * Removes a trailing slash from a route.
    *
    * @param path Path with trailing slash
    * @return     Redirect to proper route
    */
  def removeTrail(path: String): Action[AnyContent] = Action(MovedPermanently(s"/$path"))

  /**
    * Show the activities page for a user
    */
  def showActivities(user: String): Action[AnyContent] =
    Authenticated.andThen(PermissionAction(Permission.Reviewer)).asyncF { implicit request =>
      val dbProgram = for {
        reviewActibity <- AppQueries.getReviewActivity(user).to[Vector]
        flagActivity   <- AppQueries.getFlagActivity(user).to[Vector]
      } yield (reviewActibity, flagActivity)

      service.runDbCon(dbProgram).map {
        case (reviewActivity, flagActivity) =>
          val activities       = reviewActivity.map(_.asRight[FlagActivity]) ++ flagActivity.map(_.asLeft[ReviewActivity])
          val sortedActivities = activities.sortWith(sortActivities)
          Ok(views.users.admin.activity(user, sortedActivities))
      }
    }

  /**
    * Compares 2 activities (supply a [[Review]] or [[Flag]]) to decide which came first
    * @param o1 Review / Flag
    * @param o2 Review / Flag
    * @return Boolean
    */
  def sortActivities(
      o1: Either[FlagActivity, ReviewActivity],
      o2: Either[FlagActivity, ReviewActivity]
  ): Boolean = {
    val o1Time: Long = o1 match {
      case Right(review) => review.endedAt.getOrElse(Instant.EPOCH).toEpochMilli
      case _             => 0
    }
    val o2Time: Long = o2 match {
      case Left(flag) => flag.resolvedAt.getOrElse(Instant.EPOCH).toEpochMilli
      case _          => 0
    }
    o1Time > o2Time
  }

  /**
    * Show stats
    * @return
    */
  def showStats(from: Option[String], to: Option[String]): Action[AnyContent] =
    Authenticated.andThen(PermissionAction[AuthRequest](Permission.ViewStats)).asyncF { implicit request =>
      def parseTime(time: Option[String], default: LocalDate) =
        time.map(s => Try(LocalDate.parse(s)).toOption).getOrElse(Some(default))

      val res = for {
        fromTime <- parseTime(from, LocalDate.now().minus(10, ChronoUnit.DAYS))
        toTime   <- parseTime(to, LocalDate.now())
        if fromTime.isBefore(toTime)
      } yield {
        service.runDbCon(AppQueries.getStats(fromTime, toTime).to[List]).map { stats =>
          Ok(views.users.admin.stats(stats, fromTime, toTime))
        }
      }

      res.getOrElse(IO.fail(BadRequest))
    }

  def showLog(
      oPage: Option[Int],
      userFilter: Option[DbRef[User]],
      projectFilter: Option[DbRef[Project]],
      versionFilter: Option[DbRef[Version]],
      pageFilter: Option[DbRef[Page]],
      actionFilter: Option[Int],
      subjectFilter: Option[DbRef[_]]
  ): Action[AnyContent] = Authenticated.andThen(PermissionAction(Permission.ViewLogs)).asyncF { implicit request =>
    val pageSize = 50
    val page     = oPage.getOrElse(1)
    val offset   = (page - 1) * pageSize

    (
      service.runDbCon(
        AppQueries
          .getLog(oPage, userFilter, projectFilter, versionFilter, pageFilter, actionFilter, subjectFilter)
          .to[Vector]
      ),
      ModelView.now(LoggedActionModel).size
    ).parMapN { (actions, size) =>
      Ok(
        views.users.admin.log(
          actions,
          pageSize,
          offset,
          page,
          size,
          userFilter,
          projectFilter,
          versionFilter,
          pageFilter,
          actionFilter,
          subjectFilter,
          request.headerData.globalPerm(Permission.ViewIp)
        )
      )
    }
  }

  def UserAdminAction: ActionBuilder[AuthRequest, AnyContent] =
    Authenticated.andThen(PermissionAction(Permission.EditAllUserSettings))

  def userAdmin(user: String): Action[AnyContent] = UserAdminAction.asyncF { implicit request =>
    for {
      u    <- users.withName(user).toZIOWithError(notFound)
      orga <- u.toMaybeOrganization(ModelView.now(Organization)).value
      projectRoles <- orga.fold(
        service.runDBIO(u.projectRoles(ModelView.raw(ProjectUserRole)).result)
      )(orga => IO.succeed(Nil))
      t2 <- (
        UserData.of(request, u),
        ZIO.foreachPar(projectRoles)(_.project[Task].orDie),
        OrganizationData.of[Task, ParTask](orga).value.orDie
      ).parTupled
      (userData, projects, orgaData) = t2
    } yield {
      val pr = projects.zip(projectRoles)
      Ok(views.users.admin.userAdmin(userData, orgaData, pr.map(t => t._1.obj -> t._2)))
    }
  }

  def updateUser(userName: String): Action[(String, String, String)] =
    UserAdminAction.asyncF(parse.form(forms.UserAdminUpdate)) { implicit request =>
      users
        .withName(userName)
        .toZIOWithError(NotFound)
        .flatMap { user =>
          //TODO: Make the form take json directly
          val (thing, action, data) = request.body
          import play.api.libs.json._
          val json       = Json.parse(data)
          val orgDossier = MembershipDossier.organizationHasMemberships

          def updateRoleTable[M0 <: UserRoleModel[M0]: ModelQuery](model: ModelCompanion[M0])(
              modelAccess: ModelView.Now[UIO, model.T, Model[M0]],
              allowedCategory: RoleCategory,
              ownerType: Role,
              transferOwner: Model[M0] => UIO[Model[M0]]
          ): IO[Either[Status, Unit], Status] = {
            val id = (json \ "id").as[DbRef[M0]]
            action match {
              case "setRole" =>
                modelAccess.get(id).toZIO.mapError(Right.apply).flatMap { role =>
                  val roleType = Role.withValue((json \ "role").as[String])

                  if (roleType == ownerType)
                    transferOwner(role).const(Ok)
                  else if (roleType.category == allowedCategory && roleType.isAssignable)
                    service.update(role)(_.withRole(roleType)).const(Ok)
                  else
                    IO.fail(Left(BadRequest))
                }
              case "setAccepted" =>
                modelAccess
                  .get(id)
                  .toZIO
                  .mapError(Right.apply)
                  .flatMap(role => service.update(role)(_.withAccepted((json \ "accepted").as[Boolean])).as(Ok))
              case "deleteRole" =>
                modelAccess
                  .get(id)
                  .filter(_.role.isAssignable)
                  .toZIO
                  .mapError(Right.apply)
                  .flatMap(service.delete(_).as(Ok))
            }
          }

          def transferOrgOwner(r: Model[OrganizationUserRole]) =
            r.organization[Task]
              .orDie
              .flatMap(_.transferOwner(r.userId))
              .const(r)

          val res: IO[Either[Status, Unit], Status] = thing match {
            case "orgRole" =>
              val update = updateRoleTable(OrganizationUserRole)(
                user.organizationRoles(ModelView.now(OrganizationUserRole)),
                RoleCategory.Organization,
                Role.OrganizationOwner,
                transferOrgOwner,
              )

              val isEmpty: IO[Either[Status, Unit], Boolean] = user
                .toMaybeOrganization(ModelView.now(Organization))
                .isEmpty

              isEmpty.ifM(update, IO.fail(Right(())))
            case "memberRole" =>
              user.toMaybeOrganization(ModelView.now(Organization)).toZIO.mapError(Right.apply).flatMap { orga =>
                updateRoleTable(OrganizationUserRole)(
                  orgDossier.roles(orga),
                  RoleCategory.Organization,
                  Role.OrganizationOwner,
                  transferOrgOwner,
                )
              }
            case "projectRole" =>
              val update = updateRoleTable(ProjectUserRole)(
                user.projectRoles(ModelView.now(ProjectUserRole)),
                RoleCategory.Project,
                Role.ProjectOwner,
                r => r.project[Task].orDie.flatMap(_.transferOwner[Task](r.userId).orDie).as(r),
              )

              val isEmpty: IO[Either[Status, Unit], Boolean] =
                user.toMaybeOrganization(ModelView.now(Organization)).isEmpty

              isEmpty.ifM(update, IO.fail(Right(())))
            case _ => IO.fail(Right(()))
          }

          res.mapError {
            case Right(_) => BadRequest
            case Left(e)  => e
          }
        }
    }

  def showProjectVisibility(): Action[AnyContent] =
    Authenticated.andThen(PermissionAction[AuthRequest](Permission.Reviewer)).asyncF { implicit request =>
      (
        service.runDbCon(AppQueries.getVisibilityNeedsApproval.to[Vector]),
        service.runDbCon(AppQueries.getVisibilityWaitingProject.to[Vector])
      ).mapN { (needsApproval, waitingProject) =>
        Ok(views.users.admin.visibility(needsApproval, waitingProject))
      }
    }

  def swagger(): Action[AnyContent] = OreAction { implicit request =>
    Ok(views.swagger())
  }
}
