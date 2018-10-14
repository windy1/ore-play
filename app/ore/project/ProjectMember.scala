package ore.project

import scala.concurrent.{ExecutionContext, Future}

import db.{ModelService, ObjectReference}
import models.project.Project
import models.user.role.ProjectUserRole
import ore.permission.scope.Scope
import ore.user.Member

/**
  * Represents a member of a [[Project]].
  *
  * @param project  Project this Member is a part of
  * @param userId   Member user ID
  */
class ProjectMember(val project: Project, val userId: ObjectReference) extends Member[ProjectUserRole] {

  override def roles(implicit ec: ExecutionContext, service: ModelService): Future[Set[ProjectUserRole]] =
    this.user.flatMap(user => this.project.memberships.getRoles(project, user))
  override val scope: Scope = this.project.scope

  /**
    * Returns the Member's top role.
    *
    * @return Top role
    */
  override def headRole(implicit ec: ExecutionContext, service: ModelService): Future[ProjectUserRole] =
    this.roles.map(_.maxBy(_.role.trust))
}
