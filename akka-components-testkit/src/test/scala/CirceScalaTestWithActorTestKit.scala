package net.sc8s.akka.components.testkit

import akka.actor.testkit.typed.scaladsl.ActorTestKit.ApplicationTestConfig
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.persistence.testkit.{PersistenceTestKitPlugin, PersistenceTestKitSnapshotPlugin}
import com.typesafe.config.{Config, ConfigFactory}
import net.sc8s.akka.circe.{CirceSerializer, CirceSerializerRegistry}

abstract class CirceScalaTestWithActorTestKit(
                                                circeSerializerRegistry: CirceSerializerRegistry,
                                                additionalConfig: Config = ConfigFactory.empty(),
                                              ) extends akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit(akka.actor.ActorSystem(
  "scalatest",
  additionalConfig
    .withFallback(PersistenceTestKitPlugin.config)
    .withFallback(PersistenceTestKitSnapshotPlugin.config)
    .withFallback(ConfigFactory.load())
    .withFallback(ApplicationTestConfig),
  setup = CirceSerializerRegistry.actorSystemSetupFor(circeSerializerRegistry),
).toTyped) {
  def this(
            serializers: Seq[CirceSerializer[_]],
          ) = this(new CirceSerializerRegistry {
    override def serializers = serializers
  }, ConfigFactory.empty())

  def this(
            serializers: Seq[CirceSerializer[_]],
            additionalConfig: Config,
          ) = this(new CirceSerializerRegistry {
    override def serializers = serializers
  }, additionalConfig)
}
