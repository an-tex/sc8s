import akka.actor.ActorSystem
import com.sksamuel.elastic4s.ElasticClient
import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.akka.{AkkaHttpClient, AkkaHttpClientSettings}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

implicit val actorSystem = ActorSystem()

//val elasticClient= ElasticClient(JavaClient(ElasticProperties("http://localhost:9210")))

val elasticClient = ElasticClient(AkkaHttpClient(AkkaHttpClientSettings(Seq("localhost:9210"))))
Await.result(elasticClient.execute(
  putMapping("image_signatures").meta(Map("moin" -> "digga"))
), 5.minutes).result

Await.result(elasticClient.execute(
  getIndex("image_signatures")
), 5.minutes).result.head._2.mappings.meta
