package mu.moin.elastic.api

import akka.NotUsed
import com.lightbend.lagom.scaladsl.api.transport.Method
import com.lightbend.lagom.scaladsl.api.{Descriptor, Service, ServiceCall}

trait ElasticService extends Service {
  def migrateIndices(indices: Seq[String] = Nil, forceReindex: Option[Boolean]): ServiceCall[NotUsed, NotUsed]

  def evolveDocuments(indices: Seq[String] = Nil): ServiceCall[NotUsed, NotUsed]

  def batchUpdate(index: String, job: String): ServiceCall[NotUsed, NotUsed]

  val apiPrefix: String

  abstract override def descriptor: Descriptor = super.descriptor.addCalls({
    import Service._
    Seq(
      restCall(Method.POST, s"$apiPrefix/elastic/index/migrate?indices&forceReindex", migrateIndices _),
      restCall(Method.POST, s"$apiPrefix/elastic/documents/evolve?indices", evolveDocuments _),
      restCall(Method.POST, s"$apiPrefix/elastic/documents/batch-update?index&job", batchUpdate _),
    )
  }: _ *)
}
