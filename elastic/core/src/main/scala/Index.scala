package net.sc8s.elastic

import com.github.dwickern.macros.NameOf.qualifiedNameOf
import com.github.dwickern.macros.NameOfImpl
import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s._
import com.sksamuel.elastic4s.analysis.Analysis
import com.sksamuel.elastic4s.circe._
import com.sksamuel.elastic4s.fields.ElasticField
import com.sksamuel.elastic4s.requests.bulk.BulkRequest
import com.sksamuel.elastic4s.requests.delete.{DeleteByIdRequest, DeleteByQueryRequest}
import com.sksamuel.elastic4s.requests.indexes.IndexRequest
import com.sksamuel.elastic4s.requests.searches.SearchRequest
import com.sksamuel.elastic4s.requests.searches.queries.Query
import com.sksamuel.elastic4s.requests.update.UpdateRequest

import io.circe.syntax.EncoderOps
import io.circe.{Codec, Json}
import net.sc8s.circe.CodecConfiguration
import net.sc8s.elastic.Index.BatchUpdate
import net.sc8s.schevo.circe.SchevoCirce

import java.time.format.DateTimeFormatter
import scala.concurrent.Future
import scala.language.experimental.macros
import scala.reflect.runtime.universe.{TypeTag, typeOf}

abstract class Index(
                      // baseName without prefixes, should not be accessible from outside to avoid accidental access of non-prefixed indices
                      baseName: String
                    ) extends SchevoCirce with CodecConfiguration {

  val indexSetup: IndexSetup

  final lazy val name = s"${indexSetup.indexNamePrefix.getOrElse("")}$baseName"

  // mixin StringId for defaults
  type Id
  /* don't do this as Id could be an "external", non extendable class
  type Id <: {
    val hitId: String
  }
   */

  override type Latest <: LatestT with Version {
    // the id occurs twice in the hit.id and inside the document itself. in the first iteration the id was saved only in the hit.id but in the case of e.g. JsonId's this makes subsets of the id not queryable (as elastic handles the JsonId as a String) so let's live with this duplication
    val id: Id
  }

  // mappings deletion is not supported (but still can happen if an reindex occurs due to a changed mapping)
  val mappings = Seq.empty[ElasticField]

  val analysis = Analysis(Nil)

  val settings = Map.empty[String, Any]

  val batchUpdates = Seq.empty[BatchUpdate[Latest]]

  final lazy val mappingsHash = mappings.toString.hashCode.toString

  final lazy val analysisHash = analysis.toString.hashCode.toString

  final lazy val settingsHash = settings.toString.hashCode.toString

  def hitIdFromId(id: Id): Json

  lazy implicit val latestTraitIndexable: Indexable[Latest] = indexableWithCirce(implicitly)

  lazy implicit val versionedHitReader: HitReader[Latest] = hitReaderWithCirce(codec)
  // copy&paste this. no clue how to define it in here, macros?
  //override val latestVersion = latestVersionHelper[LatestCaseClass]
  val latestVersion: String

  def latestVersionHelper[T <: LatestCaseClass : TypeTag] = typeOf[T].typeSymbol.name.decodedName.toString

  implicit val codec: Codec[Latest]

  import indexSetup.actorSystem.executionContext
  import indexSetup.elasticClient

  private def execute[T, U](t: T)(implicit
                                  handler: Handler[T, U],
                                  manifest: Manifest[U],
                                  options: CommonRequestOptions
  ): Future[U] = elasticClient.execute(t).map(_.result)

  def encodeId(id: Id) = hitIdFromId(id).noSpacesSortKeys

  def index(latest: Latest, transformRequest: IndexRequest => IndexRequest = identity) = execute(indexRequest(latest, transformRequest))

  def bulkIndex(lastests: Latest*) = execute(bulkIndexRequest(lastests))

  def bulkIndex(lastests: Seq[Latest], transformRequest: BulkRequest => BulkRequest = identity) = execute(bulkIndexRequest(lastests, transformRequest))

  def indexRequest(latest: Latest, transformRequest: IndexRequest => IndexRequest = identity) =
    transformRequest(indexInto(name) id encodeId(latest.id) doc latest refresh indexSetup.refreshPolicy)

  def bulkIndexRequest(latests: Seq[Index.this.Latest], transformRequest: BulkRequest => BulkRequest = identity) =
    transformRequest(ElasticDsl.bulk(latests.map(indexRequest(_))) refresh indexSetup.refreshPolicy)

  def bulkDelete(id: Id*) = execute(bulkDeleteRequest(id))

  def bulkDeleteRequest(id: Seq[Id]) =
    ElasticDsl.bulk(id.map(deleteRequest(_))) refresh indexSetup.refreshPolicy

  def bulk(bulkDelete: Seq[Id], bulkIndex: Seq[Latest]) = execute(bulkRequest(bulkDelete, bulkIndex))

  def bulkRequest(bulkDelete: Seq[Id], bulkIndex: Seq[Latest]) =
    ElasticDsl.bulk(bulkDelete.map(deleteRequest(_)) ++ bulkIndex.map(indexRequest(_))) refresh indexSetup.refreshPolicy

  def get(id: Id): Future[Option[Latest]] =
    execute(getRequest(id)).map(_.toOpt[Latest])

  private def getRequest(id: Id) = ElasticDsl.get(name, encodeId(id))

  def delete(id: Id, transformRequest: DeleteByIdRequest => DeleteByIdRequest = identity) =
    execute(deleteRequest(id, transformRequest))

  def deleteAll() =
    execute(deleteAllRequest())

  def deleteRequest(id: Id, transformRequest: DeleteByIdRequest => DeleteByIdRequest = identity) = transformRequest(deleteById(name, encodeId(id)) refresh indexSetup.refreshPolicy)

  def deleteAllRequest() = deleteByQuery(name, matchAllQuery()) refresh indexSetup.refreshPolicy

  def deleteQuery(query: Query, transformRequest: DeleteByQueryRequest => DeleteByQueryRequest = identity) = execute(deleteQueryRequest(query, transformRequest))

  def deleteQueryRequest(query: Query, transformRequest: DeleteByQueryRequest => DeleteByQueryRequest = identity) = transformRequest(deleteByQuery(name, query) refresh indexSetup.refreshPolicy)

  def update(id: Id, transformRequest: UpdateRequest => UpdateRequest) = execute(updateRequest(id, transformRequest))

  def updateRequest(id: Id, transformRequest: UpdateRequest => UpdateRequest = identity) = transformRequest(updateById(name, encodeId(id)) refresh indexSetup.refreshPolicy)

  def updateField(id: Id, field: Latest => Any, value: Any) =
    execute(updateFieldRequest(id, field, value))

  def updateFieldRequest(id: Id, field: Latest => Any, value: Any) = updateRequest(id, _ doc qualifiedNameOf[Latest](field) -> value)

  def fieldName(expr: Latest => Any): String = macro NameOfImpl.qualifiedNameOf

  def search(searchRequest: SearchRequest => SearchRequest = identity) = execute(searchRequest(ElasticDsl.search(name))).map(_.hits.hits.toSeq.map(_.to[Latest]))

  def searchHits(searchRequest: SearchRequest => SearchRequest = identity) = execute(searchRequest(ElasticDsl.search(name))).map(_.hits.hits.toSeq.map(hit => hit -> hit.to[Latest]))

  def searchResponse(searchRequest: SearchRequest => SearchRequest = identity) = execute(searchRequest(ElasticDsl.search(name)))

  def multiSearch(searchRequests: (SearchRequest => SearchRequest)*) = execute(ElasticDsl.multi(searchRequests.map(_(ElasticDsl.search(name))))).map(_.to[Latest])

  def multiSearchHits(searchRequests: (SearchRequest => SearchRequest)*) = execute(ElasticDsl.multi(searchRequests.map(_(ElasticDsl.search(name))))).map(_.successes.flatMap(_.hits.hits.toSeq.map(hit => hit -> hit.to[Latest])))

  def multiSearchResponse(searchRequests: (SearchRequest => SearchRequest)*) = execute(ElasticDsl.multi(searchRequests.map(_(ElasticDsl.search(name)))))
}

object Index {
  val indexNameSuffixFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS")

  case class BatchUpdate[T](job: String, update: T => T)

  abstract class StringId(baseName: String) extends Index(baseName) {
    type Id = String

    override def hitIdFromId(id: Id) = id.asJson
  }

  abstract class LongId(baseName: String) extends Index(baseName) {
    type Id = Long

    override def hitIdFromId(id: Id) = id.asJson
  }

  abstract class JsonId(baseName: String) extends Index(baseName) {
    implicit val idCodec: Codec[Id]

    override def hitIdFromId(id: Id) = id.asJson
  }

  abstract class ExternalJsonId[IdT](baseName: String)(override implicit val idCodec: Codec[IdT]) extends JsonId(baseName) {
    type Id = IdT
  }

  implicit class KeywordSuffix(field: String) {
    def keyword = field + ".keyword"
  }
}
