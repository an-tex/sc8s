package net.sc8s.elastic.lagom

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import com.sksamuel.elastic4s.ElasticClient
import com.sksamuel.elastic4s.akka.{AkkaHttpClient, AkkaHttpClientSettings}
import com.softwaremill.macwire.wire
import com.typesafe.config.Config
import net.sc8s.elastic.{Evolver, Index, IndexSetup}

trait ElasticComponents {
  val actorSystem: ActorSystem

  val elasticIndices: Set[Index]

  val config: Config

  implicit val indexSetup: IndexSetup = IndexSetup(elasticClient, actorSystem.toTyped)

  lazy implicit val elasticClient = ElasticClient(AkkaHttpClient(AkkaHttpClientSettings())(actorSystem))

  lazy val evolver: Evolver.Wiring = Evolver.init(wire[Evolver.Component])(actorSystem.toTyped)
}

