package db.impl.access

import scala.language.higherKinds

import java.io.IOException

import db.impl.query.SharedQueries
import discourse.OreDiscourseApi
import ore.OreConfig
import ore.db.access.ModelView
import ore.db.impl.OrePostgresDriver.api._
import ore.db.impl.schema.{PageTable, ProjectTable, VersionTable}
import ore.db.{Model, ModelService}
import ore.models.project._
import ore.models.project.io.ProjectFiles
import ore.util.StringUtils._
import ore.util.{FileUtils, OreMDC}
import util.syntax._
import util.{FileIO, TaskUtils}

import cats.Parallel
import cats.effect.syntax.all._
import cats.instances.option._
import cats.instances.vector._
import cats.syntax.all._
import cats.tagless.autoFunctorK
import com.google.common.base.Preconditions._
import com.typesafe.scalalogging.LoggerTakingImplicit

@autoFunctorK
trait ProjectBase[+F[_]] {

  def missingFile: F[Seq[Model[Version]]]

  def refreshHomePage(logger: LoggerTakingImplicit[OreMDC])(implicit mdc: OreMDC): F[Unit]

  /**
    * Returns the Project with the specified owner name and name.
    *
    * @param owner  Owner name
    * @param name   Project name
    * @return       Project with name
    */
  def withName(owner: String, name: String): F[Option[Model[Project]]]

  /**
    * Returns the Project with the specified owner name and URL slug, if any.
    *
    * @param owner  Owner name
    * @param slug   URL slug
    * @return       Project if found, None otherwise
    */
  def withSlug(owner: String, slug: String): F[Option[Model[Project]]]

  /**
    * Returns the Project with the specified plugin ID, if any.
    *
    * @param pluginId Plugin ID
    * @return         Project if found, None otherwise
    */
  def withPluginId(pluginId: String): F[Option[Model[Project]]]

  /**
    * Returns true if the Project's desired slug is available.
    *
    * @return True if slug is available
    */
  def isNamespaceAvailable(owner: String, slug: String): F[Boolean]

  /**
    * Returns true if the specified project exists.
    *
    * @return True if exists
    */
  def exists(owner: String, name: String): F[Boolean]

  /**
    * Renames the specified [[Project]].
    *
    * @param project  Project to rename
    * @param name     New name to assign Project
    */
  def rename(project: Model[Project], name: String): F[Either[String, Unit]]

  def prepareDeleteVersion(version: Model[Version]): F[Model[Project]]

  /**
    * Irreversibly deletes this version.
    */
  def deleteVersion(version: Model[Version])(implicit mdc: OreMDC): F[Model[Project]]

  /**
    * Irreversibly deletes this project.
    *
    * @param project Project to delete
    */
  def delete(project: Model[Project])(implicit mdc: OreMDC): F[Int]

  def queryProjectPages(project: Model[Project]): F[Seq[(Model[Page], Seq[Model[Page]])]]
}

object ProjectBase {

  /**
    * Default live implementation of [[ProjectBase]]
    */
  class ProjectBaseF[F[_]](
      implicit service: ModelService[F],
      config: OreConfig,
      forums: OreDiscourseApi[F],
      fileManager: ProjectFiles[F],
      fileIO: FileIO[F],
      F: cats.effect.Effect[F]
  ) extends ProjectBase[F] {

    def missingFile: F[Seq[Model[Version]]] = {
      def allVersions =
        for {
          v <- TableQuery[VersionTable]
          p <- TableQuery[ProjectTable] if v.projectId === p.id
        } yield (p.ownerName, p.name, v)

      println(s"Foobar: ${allVersions.result.statements.toVector}")

      service.runDBIO(allVersions.result).flatMap { versions =>
        fileIO
          .traverseLimited(versions.toVector) {
            case t @ (ownerNamer, name, version) =>
              val res = F
                .bracket(F.delay(this.fileManager.getVersionDir(ownerNamer, name, version.name)))(
                  versionDir => fileIO.notExists(versionDir.resolve(version.fileName))
                )(_ => F.unit)
                .recover {
                  case _: IOException =>
                    //Invalid file name
                    false
                }

              res.tupleLeft(t)
          }
          .map {
            _.collect {
              case ((_, _, v), true) => v
            }
          }
      }
    }

    def refreshHomePage(logger: LoggerTakingImplicit[OreMDC])(implicit mdc: OreMDC): F[Unit] =
      service
        .runDbCon(SharedQueries.refreshHomeView.run)
        .runAsync(TaskUtils.logCallback("Failed to refresh home page", logger))
        .to[F]

    def withName(owner: String, name: String): F[Option[Model[Project]]] =
      ModelView
        .now(Project)
        .find(p => p.ownerName.toLowerCase === owner.toLowerCase && p.name.toLowerCase === name.toLowerCase)
        .value

    def withSlug(owner: String, slug: String): F[Option[Model[Project]]] =
      ModelView
        .now(Project)
        .find(p => p.ownerName.toLowerCase === owner.toLowerCase && p.slug.toLowerCase === slug.toLowerCase)
        .value

    def withPluginId(pluginId: String): F[Option[Model[Project]]] =
      ModelView.now(Project).find(equalsIgnoreCase(_.pluginId, pluginId)).value

    def isNamespaceAvailable(owner: String, slug: String): F[Boolean] =
      withSlug(owner, slug).map(_.isEmpty)

    def exists(owner: String, name: String): F[Boolean] =
      withName(owner, name).map(_.isDefined)

    def rename(
        project: Model[Project],
        name: String
    ): F[Either[String, Unit]] = {
      val newName = compact(name)
      val newSlug = slugify(newName)

      val doRename = {
        val fileOp      = this.fileManager.renameProject(project.ownerName, project.name, newName)
        val renameModel = service.update(project)(_.copy(name = newName, slug = newSlug))

        // Project's name alter's the topic title, update it
        val dbOp =
          if (project.topicId.isDefined)
            forums.updateProjectTopic(project).void <* renameModel
          else
            renameModel.void

        dbOp <* fileOp
      }

      if (!config.isValidProjectName(name)) F.pure(Left("Invalid project name"))
      else {
        for {
          isAvailable <- this.isNamespaceAvailable(project.ownerName, newSlug)
          _           <- if (isAvailable) doRename else F.unit
        } yield Either.cond(isAvailable, (), "Name not available")
      }
    }

    def prepareDeleteVersion(version: Model[Version]): F[Model[Project]] = {
      for {
        proj <- version.project
        size <- proj.versions(ModelView.now(Version)).count(_.visibility === (Visibility.Public: Visibility))
        _ = checkArgument(size > 1, "only one public version", "")
      } yield proj
    }

    /**
      * Irreversibly deletes this version.
      */
    def deleteVersion(version: Model[Version])(implicit mdc: OreMDC): F[Model[Project]] = {
      for {
        proj <- prepareDeleteVersion(version)
        _ <- {
          val versionDir = this.fileManager.getVersionDir(proj.ownerName, proj.name, version.name)
          fileIO.executeBlocking(FileUtils.deleteDirectory(versionDir)) *> service.delete(version)
        }
      } yield proj
    }

    /**
      * Irreversibly deletes this project.
      *
      * @param project Project to delete
      */
    def delete(project: Model[Project])(implicit mdc: OreMDC): F[Int] = {
      val fileEff = fileIO.executeBlocking(
        FileUtils.deleteDirectory(this.fileManager.getProjectDir(project.ownerName, project.name))
      )
      val eff =
        if (project.topicId.isDefined)
          forums.deleteProjectTopic(project).void
        else F.unit
      // TODO: Instead, move to the "projects_deleted" table just in case we couldn't delete the topic
      eff *> service.delete(project) <* fileEff
    }

    def queryProjectPages(project: Model[Project]): F[Seq[(Model[Page], Seq[Model[Page]])]] = {
      val tablePage = TableQuery[PageTable]
      val pagesQuery = for {
        (pp, p) <- tablePage.joinLeft(tablePage).on(_.id === _.parentId)
        if pp.projectId === project.id.value && pp.parentId.isEmpty
      } yield (pp, p)

      service.runDBIO(pagesQuery.result).map(_.groupBy(_._1)).map { grouped => // group by parent page
        // Sort by key then lists too
        grouped.toSeq.sortBy(_._1.name).map {
          case (pp, p) =>
            (pp, p.flatMap(_._2).sortBy(_.name))
        }
      }
    }
  }

  def apply[F[_]](implicit projectBase: ProjectBase[F]): ProjectBase[F] = projectBase
}
