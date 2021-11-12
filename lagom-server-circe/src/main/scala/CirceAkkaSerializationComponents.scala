package net.sc8s.lagom.circe

import akka.actor.ActorSystem
import com.lightbend.lagom.scaladsl.server.LagomApplication
import com.typesafe.config.Config
import net.sc8s.akka.circe.CirceSerializerRegistry
import play.api.{Configuration, Environment}

trait CirceAkkaSerializationComponents {
  _: LagomApplication =>

  def circeSerializerRegistry: CirceSerializerRegistry = new CirceSerializerRegistry {
    override def serializers = Nil
  }

  override lazy val actorSystem: ActorSystem =
    ActorSystemProvider.start(config, environment, circeSerializerRegistry)
}

object ActorSystemProvider {
  def start(
             config: Config,
             environment: Environment,
             serializerRegistry: CirceSerializerRegistry
           ): ActorSystem = {
    val serializationSetup =
      CirceSerializerRegistry.serializationSetupFor(serializerRegistry)

    play.api.libs.concurrent.ActorSystemProvider.start(
      environment.classLoader,
      Configuration(config),
      serializationSetup
    )
  }
}

