package net.sc8s.akka.components.testkit

import ClusterComponentTestKitSpec.{Command, Event, Singleton}

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import net.sc8s.akka.circe.CirceSerializer
import net.sc8s.akka.components.ClusterComponent
import net.sc8s.logstage.elastic.Logging
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ClusterComponentTestKitSpec extends net.sc8s.lagom.circe.testkit.ScalaTestWithActorTestKit(ClusterComponentTestKitSpec.Singleton.serializers ++ ClusterComponentTestKitSpec.SingletonEventSourced.serializers ++ ClusterComponentTestKitSpec.SingletonEventSourcedWithSnapshots.serializers) with AnyWordSpecLike with Matchers with ClusterComponentTestKit with Logging with MockFactory {
  "ComponentTestKit" should {
    "support Singleton" in {
      spawnComponent(new Singleton.Component) ! Command()
    }
    "support EventSourced Singleton" in {
      spawnComponent(new ClusterComponentTestKitSpec.SingletonEventSourced.Component)
        .runCommand(Command())
        .event shouldBe Event()
    }
    "support EventSourced Singleton with Snapshots" in {
      spawnComponent(new ClusterComponentTestKitSpec.SingletonEventSourcedWithSnapshots.Component)
        .runCommand(Command())
        .event shouldBe Event()
    }
    "support Sharded" in {
      spawnComponent(new ClusterComponentTestKitSpec.Sharded.Component)("entityId") ! Command()
    }
    "support EventSourced Sharded" in {
      spawnComponent(new ClusterComponentTestKitSpec.ShardedEventSourced.Component)("entityId")
        .runCommand(Command())
        .event shouldBe Event()
    }
    "support EventSourced Sharded with Snapshots" in {
      spawnComponent(new ClusterComponentTestKitSpec.ShardedEventSourcedWithSnapshots.Component)("entityId")
        .runCommand(Command())
        .event shouldBe Event()
    }
    "support Sharded and entityRefProbes using scalamock" in {
      val entityRefMock = mockFunction[String, TestProbe[ClusterComponentTestKitSpec.ShardedEntityRefMock.SerializableCommand]]

      val testProbe = TestProbe[ClusterComponentTestKitSpec.ShardedEntityRefMock.SerializableCommand]()
      entityRefMock.expects("entityIdX").returns(testProbe)

      spawnComponentWithEntityRefProbes(new ClusterComponentTestKitSpec.ShardedEntityRefMock.Component)("entityId", entityRefMock) ! Command()

      testProbe.expectMessage(Command())
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

  object Singleton extends ClusterComponent.Singleton with ClusterComponent.SameSerializableCommand {
    override val name = "name"
    override type Command = ClusterComponentTestKitSpec.Command
    override val commandSerializer = CirceSerializer()

    class Component(implicit actorSystem: ActorSystem[_]) extends BaseComponent[Component] {
      override val behavior = context => Behaviors.receiveMessage {
        case Command() =>
          Behaviors.same
      }
    }
  }

  object SingletonEventSourced extends ClusterComponent.Singleton.EventSourced with ClusterComponent.SameSerializableCommand {
    override val name = "name"
    override type Command = ClusterComponentTestKitSpec.Command
    override val commandSerializer = CirceSerializer()

    override type Event = ClusterComponentTestKitSpec.Event
    override val eventSerializer = CirceSerializer()

    override type State = ClusterComponentTestKitSpec.State

    class Component(implicit actorSystem: ActorSystem[_]) extends BaseComponent[Component] {
      override val behavior = context => EventSourcedBehavior(
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
    }
  }

  object SingletonEventSourcedWithSnapshots extends ClusterComponent.Singleton.EventSourced.WithSnapshots with ClusterComponent.SameSerializableCommand {
    override val name = "name"
    override type Command = ClusterComponentTestKitSpec.Command
    override val commandSerializer = CirceSerializer()

    override type Event = ClusterComponentTestKitSpec.Event
    override val eventSerializer = CirceSerializer()

    override type State = ClusterComponentTestKitSpec.State

    class Component(implicit actorSystem: ActorSystem[_]) extends BaseComponent[Component] {
      override val behavior = context => EventSourcedBehavior(
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
    }
    override val retentionCriteria = RetentionCriteria.snapshotEvery(10, 2)
    override val stateSerializer = CirceSerializer()
  }

  object Sharded extends ClusterComponent.Sharded with ClusterComponent.SameSerializableCommand with ClusterComponent.Sharded.StringEntityId {
    override val name = "name"
    override type Command = ClusterComponentTestKitSpec.Command
    override val commandSerializer = CirceSerializer()

    class Component(implicit actorSystem: ActorSystem[_]) extends BaseComponent[Component] {
      override val behavior = context => Behaviors.receiveMessage {
        case Command() =>
          Behaviors.same
      }
    }
    override val typeKey = generateTypeKey
  }

  object ShardedEventSourced extends ClusterComponent.Sharded.EventSourced with ClusterComponent.SameSerializableCommand with ClusterComponent.Sharded.StringEntityId {
    override val name = "name"
    override type Command = ClusterComponentTestKitSpec.Command
    override val commandSerializer = CirceSerializer()

    override type Event = ClusterComponentTestKitSpec.Event
    override val eventSerializer = CirceSerializer()

    override type State = ClusterComponentTestKitSpec.State

    class Component(implicit actorSystem: ActorSystem[_]) extends BaseComponent[Component] {
      override val behavior = context => EventSourcedBehavior(
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
    }
    override val typeKey = generateTypeKey
  }

  object ShardedEventSourcedWithSnapshots extends ClusterComponent.Sharded.EventSourced.WithSnapshots with ClusterComponent.SameSerializableCommand with ClusterComponent.Sharded.StringEntityId {
    override val name = "name"
    override type Command = ClusterComponentTestKitSpec.Command
    override val commandSerializer = CirceSerializer()

    override type Event = ClusterComponentTestKitSpec.Event
    override val eventSerializer = CirceSerializer()

    override type State = ClusterComponentTestKitSpec.State

    class Component(implicit actorSystem: ActorSystem[_]) extends BaseComponent[Component] {
      override val behavior = context => EventSourcedBehavior(
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
    }
    override val retentionCriteria = RetentionCriteria.snapshotEvery(10, 2)
    override val stateSerializer = CirceSerializer()
    override val typeKey = generateTypeKey
  }

  object ShardedEntityRefMock extends ClusterComponent.Sharded with ClusterComponent.SameSerializableCommand with ClusterComponent.Sharded.StringEntityId {
    override val name = "name"
    override type Command = ClusterComponentTestKitSpec.Command
    override val commandSerializer = CirceSerializer()

    class Component(implicit actorSystem: ActorSystem[_]) extends BaseComponent[Component] {
      override val behavior = context => Behaviors.receiveMessage {
        case Command() =>
          context.entityRef("entityIdX") ! Command()
          Behaviors.same
      }
    }
    override val typeKey = generateTypeKey
  }
}
