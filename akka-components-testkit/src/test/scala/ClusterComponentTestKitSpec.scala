package net.sc8s.akka.components.testkit

import akka.Done
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import akka.stream.scaladsl.Source
import com.softwaremill.macwire.wireSet
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import net.sc8s.akka.circe.CirceSerializer
import net.sc8s.akka.components.ClusterComponent
import net.sc8s.akka.components.persistence.projection.cassandra.CassandraProjection
import net.sc8s.akka.components.testkit.ClusterComponentTestKitSpec._
import net.sc8s.logstage.elastic.Logging
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Future

class ClusterComponentTestKitSpec extends net.sc8s.lagom.circe.testkit.ScalaTestWithActorTestKit(ClusterComponentTestKitSpec.Singleton.serializers ++ ClusterComponentTestKitSpec.SingletonEventSourced.serializers ++ ClusterComponentTestKitSpec.SingletonEventSourcedWithSnapshots.serializers ++ ClusterComponentTestKitSpec.ShardedEventSourcedWithCustomEntityId.serializers) with AnyWordSpecLike with Matchers with ClusterComponentTestKit with Logging with MockFactory {
  "ComponentTestKit" should {
    "support Singleton" in {
      val value1 = spawnComponent(Singleton)(new Singleton.Component)
      value1 ! Command()
    }
    "support EventSourced Singleton" in {
      spawnComponent(SingletonEventSourced)(new SingletonEventSourced.Component(mock[ProjectionTarget]))
        .runCommand(Command())
        .event shouldBe Event()
    }
    "support EventSourced Singleton with Snapshots" in {
      spawnComponent(SingletonEventSourcedWithSnapshots)(new ClusterComponentTestKitSpec.SingletonEventSourcedWithSnapshots.Component)
        .runCommand(Command())
        .event shouldBe Event()
    }
    "support EventSourced Singleton with projection testing" in {
      val projectionTarget = mock[ProjectionTarget]
      val component = new SingletonEventSourced.Component(projectionTarget)
      val projection = testProjection(SingletonEventSourced)(component)(component.projection, Source(Seq(
        Event(),
        Event(),
      )))

      (projectionTarget.serviceCall _).expects(component.persistenceId.id)
      (projectionTarget.serviceCall _).expects(component.persistenceId.id)

      projectionTestKit.runWithTestSink(projection) { probe =>
        probe.request(2)
        probe.expectNextUnordered(Done, Done)
      }
    }
    "support Sharded" in {
      spawnComponent(Sharded)(new Sharded.Component, "entityId") ! Command()
    }
    "support EventSourced Sharded" in {
      spawnComponent(ShardedEventSourced)(new ShardedEventSourced.Component(mock[ProjectionTarget]), "entityId")
        .runCommand(Command())
        .event shouldBe Event()
    }
    "support EventSourced Sharded with custom EntityId" in {
      spawnComponent(ShardedEventSourcedWithCustomEntityId)(new ShardedEventSourcedWithCustomEntityId.Component, ShardedEventSourcedWithCustomEntityId.EntityId("id1", "id|2"))
        .runCommand(ShardedEventSourcedWithCustomEntityId.Command(_))
        .reply shouldBe Done
    }
    "support EventSourced Sharded with Snapshots" in {
      spawnComponent(ShardedEventSourcedWithSnapshots)(new ShardedEventSourcedWithSnapshots.Component, "entityId")
        .runCommand(Command())
        .event shouldBe Event()
    }
    "support EventSourced Sharded with projection testing" in {
      val projectionTarget = mock[ProjectionTarget]
      val entityId1 = "entityId1"
      val entityId2 = "entityId2"
      val component = new ShardedEventSourced.Component(projectionTarget)
      val projection = testProjection(ShardedEventSourced)(component)(component.projection, Source(Seq(
        entityId1 -> Event(),
        entityId2 -> Event(),
      )))

      (projectionTarget.serviceCall _).expects(entityId1)
      (projectionTarget.serviceCall _).expects(entityId2)

      projectionTestKit.runWithTestSink(projection) { probe =>
        probe.request(2)
        probe.expectNextUnordered(Done, Done)
      }
    }
    "support Sharded and entityRefProbes using scalamock" in {
      val entityRefMock = mockFunction[String, TestProbe[ClusterComponentTestKitSpec.ShardedEntityRefMock.SerializableCommand]]

      val testProbe = TestProbe[ShardedEntityRefMock.SerializableCommand]()
      entityRefMock.expects("entityIdX").returns(testProbe)

      spawnComponentWithEntityRefProbes(ShardedEntityRefMock)(new ShardedEntityRefMock.Component, "entityId", entityRefMock) ! Command()

      testProbe.expectMessage(Command())
    }
    "support SingletonComponent TestProbe" in {
      val (component, testProbe) = createProbe(Singleton)

      component.actorRef ! ClusterComponentTestKitSpec.Command()

      testProbe.expectMessage(ClusterComponentTestKitSpec.Command())
    }
    "support ShardedComponent TestProbe using mockFunction" in {
      val entityRefMock = mockFunction[String, TestProbe[ClusterComponentTestKitSpec.ShardedEntityRefMock.SerializableCommand]]
      val testProbe = TestProbe[ClusterComponentTestKitSpec.ShardedEntityRefMock.SerializableCommand]()
      entityRefMock.expects("entityIdX").returns(testProbe)
      val component = createProbe(ClusterComponentTestKitSpec.Sharded)(entityRefMock)

      component.entityRefFor("entityIdX") ! ClusterComponentTestKitSpec.Command()

      testProbe.expectMessage(ClusterComponentTestKitSpec.Command())
    }
    "support ShardedComponent TestProbe using pattern matching" in {
      val testProbe = TestProbe[ClusterComponentTestKitSpec.ShardedEntityRefMock.SerializableCommand]()
      val component = createProbe(ClusterComponentTestKitSpec.Sharded) {
        case "entityIdX" => testProbe
      }

      component.entityRefFor("entityIdX") ! ClusterComponentTestKitSpec.Command()

      testProbe.expectMessage(ClusterComponentTestKitSpec.Command())
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

    class Component extends BaseComponent {
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

    class Component(projectionTarget: ProjectionTarget) extends BaseComponent with CassandraProjection {
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

      val projection = createProjection("projection") {
        case (event, context) =>
          projectionTarget.serviceCall(context.persistenceId.id)
          Future.successful(Done)
      }

      override val projections = wireSet
    }
  }

  object SingletonEventSourcedWithSnapshots extends ClusterComponent.Singleton.EventSourced.WithSnapshots with ClusterComponent.SameSerializableCommand {
    override val name = "name"
    override type Command = ClusterComponentTestKitSpec.Command
    override val commandSerializer = CirceSerializer()

    override type Event = ClusterComponentTestKitSpec.Event
    override val eventSerializer = CirceSerializer()

    override type State = ClusterComponentTestKitSpec.State

    class Component extends BaseComponent {
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

      override val retentionCriteria = RetentionCriteria.snapshotEvery(10, 2)
    }
    override val stateSerializer = CirceSerializer()
  }

  object Sharded extends ClusterComponent.Sharded with ClusterComponent.SameSerializableCommand with ClusterComponent.Sharded.StringEntityId {
    override val name = "name"
    override type Command = ClusterComponentTestKitSpec.Command
    override val commandSerializer = CirceSerializer()

    class Component extends BaseComponent {
      override val behavior = context => Behaviors.receiveMessage {
        case Command() =>
          Behaviors.same
      }
    }
  }

  trait ProjectionTarget {
    def serviceCall(entityId: String): Unit
  }

  object ShardedEventSourced extends ClusterComponent.Sharded.EventSourced with ClusterComponent.SameSerializableCommand with ClusterComponent.Sharded.StringEntityId {
    override val name = "name"
    override type Command = ClusterComponentTestKitSpec.Command
    override val commandSerializer = CirceSerializer()

    override type Event = ClusterComponentTestKitSpec.Event
    override val eventSerializer = CirceSerializer()

    override type State = ClusterComponentTestKitSpec.State

    class Component(projectionTarget: ProjectionTarget) extends BaseComponent with CassandraProjection {
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

      val projection = createProjection("projection") {
        case (event, context) =>
          projectionTarget.serviceCall(context.entityId)
          Future.successful(Done)
      }

      override val projections = wireSet
    }
  }

  object ShardedEventSourcedWithCustomEntityId extends ClusterComponent.Sharded.EventSourced with ClusterComponent.SameSerializableCommand with ClusterComponent.Sharded.JsonEntityId {

    import net.sc8s.akka.circe.implicits._

    case class EntityId(id1: String, id2: String)

    override implicit val entityIdCirceCodec: Codec[EntityId] = deriveCodec

    case class Command(replyTo: ActorRef[Done])

    object Command {
      implicit val codec: Codec[Command] = deriveCodec
    }

    case class Event()

    object Event {
      implicit val codec: Codec[Event] = deriveCodec
    }

    case class State()

    class Component extends BaseComponent {
      override val behavior = { context =>
        EventSourcedBehavior[Command, Event, State](
          context.persistenceId,
          State(),
          {
            case (_, command) =>
              Effect.reply(command.replyTo)(Done)
          },
          {
            case (_, _) =>
              State()
          }
        )
      }
    }

    override val name = "name"

    override val eventSerializer = CirceSerializer[Event]()
    override val commandSerializer = CirceSerializer()
  }

  object ShardedEventSourcedWithSnapshots extends ClusterComponent.Sharded.EventSourced.WithSnapshots with ClusterComponent.SameSerializableCommand with ClusterComponent.Sharded.StringEntityId {
    override val name = "name"
    override type Command = ClusterComponentTestKitSpec.Command
    override val commandSerializer = CirceSerializer()

    override type Event = ClusterComponentTestKitSpec.Event
    override val eventSerializer = CirceSerializer()

    override type State = ClusterComponentTestKitSpec.State

    class Component extends BaseComponent {
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

      override val retentionCriteria = RetentionCriteria.snapshotEvery(10, 2)
    }
    override val stateSerializer = CirceSerializer()
  }

  object ShardedEntityRefMock extends ClusterComponent.Sharded with ClusterComponent.SameSerializableCommand with ClusterComponent.Sharded.StringEntityId {
    override val name = "name"
    override type Command = ClusterComponentTestKitSpec.Command
    override val commandSerializer = CirceSerializer()

    class Component extends BaseComponent {
      override val behavior = context => Behaviors.receiveMessage {
        case Command() =>
          context.entityRefFor("entityIdX") ! Command()
          Behaviors.same
      }
    }
  }
}
