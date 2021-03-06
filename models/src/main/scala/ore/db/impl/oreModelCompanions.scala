package ore.db.impl

import scala.language.higherKinds

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.concurrent.TimeUnit

import ore.db.impl.OrePostgresDriver.api._
import ore.db.{Model, ModelCompanion, ObjId, ObjOffsetDateTime}

import cats._
import cats.effect.Clock
import cats.syntax.all._

trait OreModelCompanion[M] extends ModelCompanion[M] {
  override val profile: OrePostgresDriver.type = ore.db.impl.OrePostgresDriver

  private def timeF[F[_]: Functor](implicit clock: Clock[F]) =
    clock
      .realTime(TimeUnit.MILLISECONDS)
      .map(t => ObjOffsetDateTime(OffsetDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneOffset.UTC)))

  /**
    * Creates the specified model in it's table.
    *
    * @param model  Model to create
    * @return       Newly created model
    */
  def insert[F[_]: Monad: Clock](model: M): F[DBIO[Model[M]]] = {
    val toInsertF = timeF[F].map(time => asDbModel(model, new ObjId.UnsafeUninitialized, time))
    toInsertF.map { toInsert =>
      baseQuery.returning(baseQuery.map(_.id)).into {
        case (m, id) => asDbModel(m, ObjId(id), m.createdAt)
      } += toInsert
    }
  }

  /**
    * Creates the specified models in it's table.
    *
    * @param models  Models to create
    * @return       Newly created models
    */
  def bulkInsert[F[_]: Monad: Clock](models: Seq[M]): F[DBIO[Seq[Model[M]]]] =
    if (models.nonEmpty) {
      val toInsertF = timeF[F].map(time => models.map(asDbModel(_, new ObjId.UnsafeUninitialized, time)))

      toInsertF.map { toInsert =>
        baseQuery
          .returning(baseQuery.map(_.id))
          .into((m, id) => asDbModel(m, ObjId(id), m.createdAt)) ++= toInsert
      }
    } else {
      val action: DBIO[Seq[Model[M]]] = DBIO.successful[Seq[Model[M]]](Nil)
      action.pure[F]
    }

  def update[F[_]: Monad](model: Model[M])(update: M => M): F[DBIO[Model[M]]] = {
    val updatedModel = model.copy(obj = update(model.obj))
    import scala.concurrent.ExecutionContext.Implicits.global //TODO: Use a ec on the same thread
    val action: DBIO[Int] = baseQuery.filter(_.id === model.id.value).update(updatedModel)
    action.map(_ => updatedModel).pure[F]
  }

  /**
    * Deletes the specified Model.
    *
    * @param model Model to delete
    */
  def delete(model: Model[M]): DBIO[Int] =
    deleteWhere(_.id === model.id.value)

  /**
    * Deletes all the models meeting the specified filter.
    *
    * @param filter     Filter to use
    */
  def deleteWhere(filter: T => Rep[Boolean]): DBIO[Int] = baseQuery.filter(filter).delete
}
abstract class ModelCompanionPartial[M, T0 <: ModelTable[M]](val baseQuery: Query[T0, Model[M], Seq])
    extends OreModelCompanion[M] {
  type T = T0
}
abstract class DefaultModelCompanion[M, T0 <: ModelTable[M]](baseQuery: Query[T0, Model[M], Seq])
    extends ModelCompanionPartial(baseQuery) {
  override def asDbModel(model: M, id: ObjId[M], time: ObjOffsetDateTime): Model[M] = Model(id, time, model)
}
