package models.user

import java.sql.Timestamp

import scala.concurrent.{ExecutionContext, Future}

import db.impl.access.UserBase
import db.impl.model.common.Expirable
import db.impl.schema.SessionTable
import db.{Model, ModelQuery, ObjId, ObjectTimestamp}
import security.spauth.SpongeAuthApi

import cats.data.OptionT
import slick.lifted.TableQuery

/**
  * Represents a persistant [[User]] session.
  *
  * @param id         Unique ID
  * @param createdAt  Instant of creation
  * @param expiration Instant of expiration
  * @param username   Username session belongs to
  * @param token      Unique token
  */
case class Session(
    id: ObjId[Session] = ObjId.Uninitialized(),
    createdAt: ObjectTimestamp = ObjectTimestamp.Uninitialized,
    expiration: Timestamp,
    username: String,
    token: String
) extends Model
    with Expirable {

  override type M = Session
  override type T = SessionTable

  /**
    * Returns the [[User]] that this Session belongs to.
    *
    * @param users UserBase instance
    * @return User session belongs to
    */
  def user(implicit users: UserBase, ec: ExecutionContext, auth: SpongeAuthApi): OptionT[Future, User] =
    users.withName(this.username)
}
object Session {
  implicit val query: ModelQuery[Session] =
    ModelQuery.from[Session](TableQuery[SessionTable], _.copy(_, _))
}
