package net.sc8s.akka.components.testkit

import akka.actor.testkit.typed.scaladsl.ActorTestKit.ApplicationTestConfig
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.actor.{ActorSystem, BootstrapSetup}
import akka.actor.setup.ActorSystemSetup
import akka.persistence.testkit.{PersistenceTestKitPlugin, PersistenceTestKitSnapshotPlugin}
import com.typesafe.config.{Config, ConfigFactory}
import net.sc8s.akka.circe.{CirceSerializer, CirceSerializerRegistry}

abstract class CirceScalaTestWithActorTestKit(
                                                circeSerializerRegistry: CirceSerializerRegistry,
                                                additionalConfig: Config = ConfigFactory.empty(),
                                              ) extends akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit(ActorSystem(
  "scalatest",
  ActorSystemSetup(
    BootstrapSetup(
      additionalConfig
        .withFallback(PersistenceTestKitPlugin.config)
        .withFallback(PersistenceTestKitSnapshotPlugin.config)
        .withFallback(ConfigFactory.load())
        .withFallback(ApplicationTestConfig),
    ),
    CirceSerializerRegistry.serializationSetupFor(circeSerializerRegistry),
  )
).toTyped) {
  def this(
            _serializers: Seq[CirceSerializer[_]],
          ) = this(new CirceSerializerRegistry {
    override def serializers = _serializers
  }, ConfigFactory.empty())

  def this(
            _serializers: Seq[CirceSerializer[_]],
            additionalConfig: Config,
          ) = this(new CirceSerializerRegistry {
    override def serializers = _serializers
  }, additionalConfig)
}
