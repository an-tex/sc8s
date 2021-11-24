package net.sc8s.lagom.akka.components

import ClusterComponent.Sharded.EntityIdCodec
import ClusterComponentSpec._

import akka.Done
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed.ClusterSingleton
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import com.softwaremill.macwire._
import com.typesafe.config.ConfigFactory
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import izumi.logstage.api.Log.CustomContext
import net.sc8s.akka.circe.CirceSerializer
import org.scalamock.scalatest.MockFactory
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future

/*
This spec is only meant to illyus
 */
class ClusterComponentSpec extends ScalaTestWithActorTestKit(ConfigFactory.parseString(
  """
    |akka.actor.provider = cluster
    |""".stripMargin)) with AnyFreeSpecLike with Matchers with MockFactory {

  "ClusterComponents" - {
    "Singletons" - {
      val clusterSingleton = stub[ClusterSingleton]

      "minimal with wiring" in {
        class Dependency

        object Component {
          def create(dependency: Dependency) = ClusterComponent.Singleton[Command, Command](
            "singleton",
            componentContext => Behaviors.receiveMessage {
              case Command() => Behaviors.same
            },
            CirceSerializer()
          )
        }

        class Service(component: ClusterComponent.SingletonComponent[Command]) {
          component.actorRef ! Command()
        }

        object ApplicationLoader {
          // in lagom production code you'd add
          //extends LagomApplication(context) with WiredClusterComponents

          val dependency = wire[Dependency]

          val component = wireWith(Component.create _).init()

          val service = wire[Service]
        }

        ApplicationLoader.service shouldBe a[Service]
      }
      "different SerializableCommand" in {
        class Dependency

        object Component {
          def create(dependency: Dependency) = ClusterComponent.Singleton[Command3, Command3.SerializableCommand](
            "singleton",
            componentContext => Behaviors.receiveMessage {
              case Command3.InternalCommand() | Command3.SerializableCommand() => Behaviors.same
            },
            CirceSerializer()
          )
        }

        class Service(component: ClusterComponent.SingletonComponent[Command3.SerializableCommand]) {
          component.actorRef ! Command3.SerializableCommand()
        }

        object ApplicationLoader {
          // in lagom production code you'd add
          //extends LagomApplication(context) with WiredClusterComponents

          val dependency = wire[Dependency]

          val component = wireWith(Component.create _).init()

          val service = wire[Service]
        }

        ApplicationLoader.service shouldBe a[Service]
      }
      "persistence" - {
        "minimal" in {
          ClusterComponent.Singleton.EventSourced[Command, Command, Event, State](
            "singleton",
            componentContext => EventSourcedBehavior(
              componentContext.persistenceId,
              State(),
              {
                case (state, command) => Effect.none
              },
              {
                case (state, event) => state
              }
            ),
            CirceSerializer(),
            CirceSerializer(),
          ).init()
        }
        "with snapshots" in {
          ClusterComponent.Singleton.EventSourced.WithSnapshots[Command, Command, Event, State](
            "singleton",
            componentContext => EventSourcedBehavior(
              componentContext.persistenceId,
              State(),
              {
                case (state, command) => Effect.none
              },
              {
                case (state, event) => state
              }
            ),
            CirceSerializer(),
            CirceSerializer(),
            CirceSerializer(),
            RetentionCriteria.snapshotEvery(100, 3),
          ).init()
        }
        "with projections" in {
          ClusterComponent.Singleton.EventSourced[Command, Command, Event, State](
            "singleton",
            componentContext => EventSourcedBehavior(
              componentContext.persistenceId,
              State(),
              {
                case (state, command) => Effect.none
              },
              {
                case (state, event) => state
              }
            ),
            CirceSerializer(),
            CirceSerializer(),
            projections = Seq(
              ClusterComponent.Singleton.Projection(
                "projection",
                {
                  case event => Future.successful(Done)
                }
              ))
          ).init()
        }
      }
    }
    "Sharded" - {
      "minimal with wiring" in {
        class Dependency

        object Component {
          def create(dependency: Dependency) = ClusterComponent.Sharded[Command, Command, String](
            "sharded",
            componentContext => Behaviors.receiveMessage {
              case Command() =>
                // that's how you can obtain an entity of this component itself as you can't dependency inject the component itself
                componentContext.entityRef("entityId")
                Behaviors.same
            },
            CirceSerializer()
          )
        }

        class Service(component: ClusterComponent.ShardedComponent[Command, String]) {
          component.entityRef("entityId") ! Command()
        }

        object ApplicationLoader {
          val dependency = wire[Dependency]

          val component = wireWith(Component.create _).init()

          val service = wire[Service]
        }

        ApplicationLoader.service shouldBe a[Service]
      }
      // does not work due to https://github.com/softwaremill/macwire/issues/187
      /*
      "minimal with wiring containing circular dependency" in {
        class Dependency

        object Component {
          def create(dependency: Dependency, clusterComponent: ClusterComponent.ShardedComponent[Command2, StringEntityId]) = ClusterComponent.Sharded.StringEntityId[Command, Command](
            "sharded1",
            componentContext => Behaviors.receiveMessage {
              case Command() =>
                Behaviors.same
            },
            CirceSerializer()
          )

          def create2(clusterComponent: ClusterComponent.ShardedComponent[Command, StringEntityId]) = ClusterComponent.Sharded.StringEntityId[Command2, Command2](
            "sharded2",
            componentContext => Behaviors.receiveMessage {
              case Command2() =>
                Behaviors.same
            },
            CirceSerializer()
          )
        }

        class Service(
                       component1: ClusterComponent.ShardedComponent[Command, StringEntityId],
                       component2: ClusterComponent.ShardedComponent[Command2, StringEntityId],
                     ) {
          component1.entityRef("entityId1") ! Command()
          component2.entityRef("entityId2") ! Command2()
        }

        object ApplicationLoader {
          val dependency = wire[Dependency]

          lazy val component1: ClusterComponent.ShardedComponent[Command, StringEntityId] = wireWith(Component.create _).init()
          lazy val component2: ClusterComponent.ShardedComponent[Command2, StringEntityId] = wireWith(Component.create2 _).init()

          val service = wire[Service]
        }

        ApplicationLoader.service shouldBe a[Service]
      }
      */
      "minimal with wiring containing circular dependency" in {

        class Service(
                       component1: ClusterComponent.ShardedComponent[Command, String],
                       component2: ClusterComponent.ShardedComponent[Command2, String],
                     ) {
          component1.entityRef("entityId1") ! Command()
          component2.entityRef("entityId2") ! Command2()
        }

        object ApplicationLoader {
          lazy val component1: ClusterComponent.ShardedComponent[Command, String] = wire[CircularComponent.C].component.init()
          lazy val component2: ClusterComponent.ShardedComponent[Command2, String] = wire[CircularComponent.C2].component.init()

          val service = wire[Service]
        }

        ApplicationLoader.service shouldBe a[Service]
      }
      "custom EntityId" in {
        ClusterComponent.Sharded[Command, Command, CompositeEntityId](
          "sharded",
          componentContext => Behaviors.receiveMessage {
            case Command() => Behaviors.same
          },
          CirceSerializer()
        ).init()
      }
      "persistence" - {
        "minimal" in {
          ClusterComponent.Sharded.EventSourced[Command, Command, Event, State, String](
            "sharded",
            componentContext => EventSourcedBehavior(
              componentContext.persistenceId,
              State(),
              {
                case (state, command) => Effect.none
              },
              {
                case (state, event) => state
              }
            ),
            CirceSerializer(),
            CirceSerializer(),
          ).init()
        }
        "with snapshots" in {
          ClusterComponent.Sharded.EventSourced.WithSnapshots[Command, Command, Event, State, String](
            "sharded",
            componentContext => EventSourcedBehavior(
              componentContext.persistenceId,
              State(),
              {
                case (state, command) => Effect.none
              },
              {
                case (state, event) => state
              }
            ),
            CirceSerializer(),
            CirceSerializer(),
            CirceSerializer(),
            RetentionCriteria.snapshotEvery(100, 3)
          ).init()
        }
        "with projections" in {
          ClusterComponent.Sharded.EventSourced[Command, Command, Event, State, String](
            "sharded",
            componentContext => EventSourcedBehavior(
              componentContext.persistenceId,
              State(),
              {
                case (state, command) => Effect.none
              },
              {
                case (state, event) => state
              }
            ),
            CirceSerializer(),
            CirceSerializer(),
            projections = Seq(ClusterComponent.Sharded.Projection(
              "projection1",
              { case (event, entityId) => Future.successful(Done)
              }
            ))
          ).init()
        }
        "with custom EntityId" in {
          ClusterComponent.Sharded.EventSourced[Command, Command, Event, State, CompositeEntityId](
            "sharded",
            componentContext => EventSourcedBehavior(
              componentContext.persistenceId,
              State(),
              {
                case (state, command) => Effect.none
              },
              {
                case (state, event) => state
              }
            ),
            CirceSerializer(),
            CirceSerializer(),
          ).init()
        }
      }
    }
  }
}

object ClusterComponentSpec {
  case class Command()
  object Command {
    implicit val codec: Codec[Command] = deriveCodec
  }

  case class Command2()
  object Command2 {
    implicit val codec: Codec[Command2] = deriveCodec
  }

  sealed trait Command3
  object Command3 {
    case class InternalCommand() extends Command3
    case class SerializableCommand() extends Command3
    implicit val codec: Codec[SerializableCommand] = deriveCodec
  }

  case class Event()
  object Event {
    implicit val codec: Codec[Event] = deriveCodec
  }

  case class State()
  object State {
    implicit val codec: Codec[State] = deriveCodec
  }

  case class CompositeEntityId(id1: String, id2: String)
  object CompositeEntityId {

    implicit val codec: EntityIdCodec[CompositeEntityId] = EntityIdCodec[CompositeEntityId](
      entityId => s"${entityId.id1}-${entityId.id2}",
      entityId => entityId.split('-').toList match {
        case id1 :: id2 :: Nil => CompositeEntityId(id1, id2)
      },
      entityId => CustomContext(
        "id1" -> entityId.id1,
        "id2" -> entityId.id2
      )
    )
  }

  // components need to be wrapped in class due to https://github.com/softwaremill/macwire/issues/187
  object CircularComponent {
    class C(clusterComponent: => ClusterComponent.ShardedComponent[Command2, String])(implicit val actorSystem: ActorSystem[_]) {
      val component = ClusterComponent.Sharded[Command, Command, String](
        "sharded1",
        componentContext => Behaviors.receiveMessage {
          case Command() =>
            Behaviors.same
        },
        CirceSerializer()
      )
    }

    class C2(clusterComponent: => ClusterComponent.ShardedComponent[Command, String])(implicit val actorSystem: ActorSystem[_]) {
      val component = ClusterComponent.Sharded[Command2, Command2, String](
        "sharded2",
        componentContext => Behaviors.receiveMessage {
          case Command2() =>
            Behaviors.same
        },
        CirceSerializer()
      )
    }
  }
}

