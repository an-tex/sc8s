package net.sc8s.schevo.circe.example.akka

import PersistentBehavior.{Command, Event, State, serializers}

import akka.actor.setup.ActorSystemSetup
import akka.actor.testkit.typed.scaladsl.ActorTestKit.ApplicationTestConfig
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.actor.{ActorSystem, BootstrapSetup}
import akka.persistence.testkit.scaladsl.{EventSourcedBehaviorTestKit, SnapshotTestKit}
import akka.persistence.testkit.{PersistenceTestKitPlugin, PersistenceTestKitSnapshotPlugin, SnapshotMeta}
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import net.sc8s.akka.circe.CirceSerializerRegistry
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.annotation.nowarn

class PersistentBehaviorSpec extends
  ScalaTestWithActorTestKit(ActorSystem(
    "spec",
    ActorSystemSetup(
      BootstrapSetup(
        ConfigFactory.parseString(
          """
            |akka.actor.serialize-messages=on
            |""".stripMargin)
          .withFallback(PersistenceTestKitPlugin.config)
          .withFallback(PersistenceTestKitSnapshotPlugin.config)
          .withFallback(ConfigFactory.load())
          .withFallback(ApplicationTestConfig),
      ),
      CirceSerializerRegistry.serializationSetupFor(new CirceSerializerRegistry {
        override def serializers = PersistentBehavior.serializers
      })
    )
  ).toTyped) with AnyWordSpecLike with Matchers {

  "PersistentBehavior" should {
    val elem1 = "elem1"
    val elem2 = "elem2"

    // can't test commands evolution as commands are not de/serialized in tests / same JVM
    "evolve commands" in new Context {
      @nowarn
      val add = Command.Add(elem1)
      eventSourcedTestKit.runCommand(add)
      eventSourcedTestKit.runCommand(Command.Add.Latest(elem2, clearBeforeAdd = false))

      eventSourcedTestKit.getState() shouldBe State.NonEmpty.Latest(Seq(elem1, elem2), 2)
    }
    "evolve events" in new Context {
      @nowarn
      val elem1Added = Event.Added(elem1)
      eventSourcedTestKit.persistenceTestKit.persistForRecovery(persistenceId.id, Seq(elem1Added, Event.Added.Latest(elem2, clearBeforeAdd = false)))
      eventSourcedTestKit.restart()

      eventSourcedTestKit.getState() shouldBe State.NonEmpty.Latest(Seq(elem1, elem2), 2)
    }
    "evolve state" in new Context {
      @nowarn
      val unversionedNonEmpty = State.NonEmpty(Seq(elem1, elem2))

      snapshotTestKit.persistForRecovery(persistenceId.id, SnapshotMeta(1) -> unversionedNonEmpty)
      eventSourcedTestKit.restart()

      eventSourcedTestKit.getState() shouldBe State.NonEmpty.Latest(unversionedNonEmpty.history, unversionedNonEmpty.history.length)
    }
  }

  trait Context {
    lazy val snapshotTestKit = SnapshotTestKit(system)

    lazy val persistenceId = PersistenceId.ofUniqueId("example")
    lazy val eventSourcedTestKit = EventSourcedBehaviorTestKit[Command, Event, State](system,
      PersistentBehavior(persistenceId)
    )
  }
}
