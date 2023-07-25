package net.sc8s.elastic

import akka.actor.typed.ActorSystem
import com.sksamuel.elastic4s.ElasticClient
import com.sksamuel.elastic4s.requests.common.RefreshPolicy

case class IndexSetup(
                       elasticClient: ElasticClient,
                       actorSystem: ActorSystem[_],
                       // mainly for tests
                       indexNamePrefix: Option[String] = None,
                       refreshImmediately: Boolean = false
                     ) {
  val refreshPolicy = if (refreshImmediately) RefreshPolicy.Immediate else RefreshPolicy.None
}
