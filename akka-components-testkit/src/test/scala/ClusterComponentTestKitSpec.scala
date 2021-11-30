package net.sc8s.akka.components.testkit

import ClusterComponentTestKitSpec.{Command, Event}

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import net.sc8s.akka.circe.CirceSerializer
import net.sc8s.akka.components.ClusterComponent
import net.sc8s.logstage.elastic.Logging
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ClusterComponentTestKitSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers with ClusterComponentTestKit with Logging {
  "ComponentTestKit" should {
    "support Singleton" in {
      val component: ActorRef[ClusterComponentTestKitSpec.Command] = spawnComponent(ClusterComponentTestKitSpec.singleton)
      component ! Command()
    }
    "support EventSourced Singleton" in {
      val component: EventSourcedBehaviorTestKit[Command, ClusterComponentTestKitSpec.Event, ClusterComponentTestKitSpec.State] = spawnComponent(ClusterComponentTestKitSpec.singletonEventSourced)
      component
        .runCommand(Command())
        .event shouldBe Event()
    }
    "support Sharded" in {
      val component: ActorRef[ClusterComponentTestKitSpec.Command] = spawnComponent(ClusterComponentTestKitSpec.sharded, "entityId")
      component ! Command()
    }
    "support Sharded EventSourced" in {
      spawnComponent(ClusterComponentTestKitSpec.shardedEventSourced, "entityId2")
        .runCommand(Command())
        .event shouldBe Event()
    }
  }
}

object ClusterComponentTestKitSpec {
  case class Command()
  object Command {
    implicit val codec: Codec[Command] = deriveCodec
  }

  case class Event()
  object Event {
    implicit val codec: Codec[Event] = deriveCodec
  }

  case class State()
  object State {
    implicit val codec: Codec[State] = deriveCodec
  }

  def singleton(implicit actorSystem: ActorSystem[_]) = ClusterComponent.Singleton[Command, Command](
    "name",
    context => Behaviors.receiveMessage {
      case Command() =>
        Behaviors.same
    },
    CirceSerializer()
  )

  def singletonEventSourced(implicit actorSystem: ActorSystem[_]) = ClusterComponent.Singleton.EventSourced[Command, Command, Event, State](
    "name",
    context => EventSourcedBehavior(
      context.persistenceId,
      State(),
      {
        case (state, command) =>
          Effect.persist(Event())
      },
      {
        case (state, event) => state
      }
    ),
    CirceSerializer(),
    CirceSerializer()
  )

  def sharded(implicit actorSystem: ActorSystem[_]) = ClusterComponent.Sharded[Command, Command, String](
    "name",
    context => Behaviors.receiveMessage {
      case Command() =>
        Behaviors.same
    },
    CirceSerializer()
  )

  def shardedEventSourced(implicit actorSystem: ActorSystem[_]) = ClusterComponent.Sharded.EventSourced[Command, Command, Event, State, String](
    "name",
    context => {
      EventSourcedBehavior(
        context.persistenceId,
        State(),
        {
          case (state, command) =>
            Effect.persist(Event())
        },
        {
          case (state, event) => state
        }
      )
    },
    CirceSerializer(),
    CirceSerializer()
  )
}
