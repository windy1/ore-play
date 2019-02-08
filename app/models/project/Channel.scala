package models.project

import scala.language.higherKinds

import db.access.QueryView
import db.impl.OrePostgresDriver.api._
import db.impl.model.common.Named
import db.impl.schema.ChannelTable
import db.{DbRef, InsertFunc, Model, ModelQuery, ObjId, ObjTimestamp}
import ore.Color
import ore.Color._
import ore.project.ProjectOwned
import util.syntax._

import slick.lifted.TableQuery

/**
  * Represents a release channel for Project Versions. Each project gets it's
  * own set of channels.
  *
  * @param id           Unique identifier
  * @param createdAt    Instant of creation
  * @param isNonReviewed Whether this channel should be excluded from the staff
  *                     approval queue
  * @param name        Name of channel
  * @param color       Color used to represent this Channel
  * @param projectId    ID of project this channel belongs to
  */
case class Channel private (
    id: ObjId[Channel],
    createdAt: ObjTimestamp,
    projectId: DbRef[Project],
    name: String,
    color: Color,
    isNonReviewed: Boolean
) extends Model
    with Named {

  override type T = ChannelTable
  override type M = Channel

  def isReviewed: Boolean = !isNonReviewed

  /**
    * Returns all Versions in this channel.
    *
    * @return All versions
    */
  def versions[V[_, _]: QueryView](view: V[Version#T, Version]): V[Version#T, Version] =
    view.filterView(_.channelId === id.value)
}

object Channel {
  def partial(
      projectId: DbRef[Project],
      name: String,
      color: Color,
      isNonReviewed: Boolean = false
  ): InsertFunc[Channel] = (id, time) => Channel(id, time, projectId, name, color, isNonReviewed)

  implicit val channelsAreOrdered: Ordering[Channel] = (x: Channel, y: Channel) => x.name.compare(y.name)

  implicit val query: ModelQuery[Channel] =
    ModelQuery.from[Channel](TableQuery[ChannelTable], _.copy(_, _))

  implicit val isProjectOwned: ProjectOwned[Channel] = (a: Channel) => a.projectId

  /**
    * The colors a Channel is allowed to have.
    */
  val Colors: Seq[Color] =
    Seq(Purple, Violet, Magenta, Blue, Aqua, Cyan, Green, DarkGreen, Chartreuse, Amber, Orange, Red)

}
