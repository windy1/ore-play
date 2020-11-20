package ore.models.project

import ore.db.{DbRef, ModelQuery}
import ore.db.impl.DefaultModelCompanion
import ore.db.impl.schema.PluginTable

import slick.lifted.TableQuery

case class Plugin(
    assetId: DbRef[Asset],
    identifier: String,
    version: String
)
object Plugin extends DefaultModelCompanion[Plugin, PluginTable](TableQuery[PluginTable]) {

  implicit val query: ModelQuery[Plugin] = ModelQuery.from(this)
}
