package net.sc8s.akka.components

import ClusterComponent.Sharded.EntityIdCodec
import ClusterComponentSpec.{CircularDependencyTest, Dependency, ShardedTestComponent, SingletonTestComponent}

import akka.Done
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
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
import scala.util.Success

/*
This spec is only meant to illustrate the usage of ClusterComponent
 */
class ClusterComponentSpec extends ScalaTestWithActorTestKit(ConfigFactory.parseString(
  """
    |akka.actor.provider = cluster
    |""".stripMargin)) with AnyFreeSpecLike with Matchers with MockFactory {

  "ClusterComponents" - {
    "Singletons" - {
      "minimal with wiring" in {
        class Service(component: SingletonTestComponent.Wiring) {
          component.actorRef ! SingletonTestComponent.Command()
        }

        object ApplicationLoader {
          // in lagom production code you'd add
          //extends LagomApplication(context) with WiredClusterComponents

          val dependency = wire[Dependency]

          val component = wire[SingletonTestComponent.Component].init()

          val service = wire[Service]
        }

        ApplicationLoader.service shouldBe a[Service]
      }
      "different SerializableCommand" in {
        object ComponentObject extends ClusterComponent.Singleton {
          sealed trait Command
          sealed trait SerializableCommand extends Command

          case class Command1() extends Command
          case class Command2() extends SerializableCommand
          object Command {
            implicit val codec: Codec[SerializableCommand] = deriveCodec
          }

          class Component(dependency: Dependency) extends BaseComponent {
            override val behavior = componentContext => Behaviors.receiveMessage {
              case Command1() => Behaviors.same
            }
          }

          override val name = "singleton"

          override val commandSerializer = CirceSerializer()
        }

        new ComponentObject.Component(new Dependency).init()
      }
      "persistence" - {
        "minimal" in {
          object ComponentObject extends ClusterComponent.Singleton.EventSourced with ClusterComponent.SameSerializableCommand {
            case class Command()
            implicit val commandCodec: Codec[SerializableCommand] = deriveCodec

            case class Event()
            implicit val eventCodec: Codec[Event] = deriveCodec

            case class State()

            class Component(dependency: Dependency) extends BaseComponent {

              override val behavior = componentContext => {
                classOf[Command]
                classOf[Event]
                classOf[State]

                EventSourcedBehavior(
                  componentContext.persistenceId,
                  State(),
                  {
                    case (state, command) => Effect.none
                  },
                  {
                    case (state, event) => state
                  })
              }
            }

            override val name = "singleton"

            override val commandSerializer = CirceSerializer()

            override val eventSerializer = CirceSerializer()
          }

          new ComponentObject.Component(new Dependency).init().actorRef ! ComponentObject.Command()
        }
        "with snapshots" in {
          object ComponentObject extends ClusterComponent.Singleton.EventSourced.WithSnapshots with ClusterComponent.SameSerializableCommand {
            case class Command()
            implicit val commandCodec: Codec[SerializableCommand] = deriveCodec

            case class Event()
            implicit val eventCodec: Codec[Event] = deriveCodec

            case class State()
            implicit val stateCodec: Codec[State] = deriveCodec

            class Component(dependency: Dependency) extends BaseComponent {
              classOf[Command]
              classOf[Event]
              classOf[State]

              override val behavior = componentContext => EventSourcedBehavior(
                componentContext.persistenceId,
                State(),
                {
                  case (state, command) => Effect.none
                },
                {
                  case (state, event) => state
                })
            }

            override val name = "singleton"

            override val commandSerializer = CirceSerializer()

            override val eventSerializer = CirceSerializer()
            override val retentionCriteria = RetentionCriteria.snapshotEvery(100, 2)
            override val stateSerializer = CirceSerializer()
          }

          new ComponentObject.Component(new Dependency).init()
        }
        "with projections" in {
          object ComponentObject extends ClusterComponent.Singleton.EventSourced with ClusterComponent.SameSerializableCommand {
            case class Command()
            implicit val commandCodec: Codec[SerializableCommand] = deriveCodec

            case class Event()
            implicit val eventCodec: Codec[Event] = deriveCodec

            case class State()

            class Component(dependency: Dependency) extends BaseComponent {
              override val behavior = componentContext => EventSourcedBehavior(
                componentContext.persistenceId,
                State(),
                {
                  case (state, command) => Effect.none
                },
                {
                  case (state, event) => state
                })

              override val projections = Seq(
                ClusterComponent.Projection(
                  "projection",
                  {
                    case (event, projectionContext) => Future.successful(Done)
                  }
                ))
            }

            override val name = "singleton"

            override val commandSerializer = CirceSerializer()

            override val eventSerializer = CirceSerializer()
          }

          new ComponentObject.Component(new Dependency).init()
        }
      }
    }
    "Sharded" - {
      "minimal with wiring" in {
        class Service(component: ShardedTestComponent.Wiring) {
          component.entityRef("entityId") ! ShardedTestComponent.Command()
        }

        object ApplicationLoader {
          // in lagom production code you'd add
          //extends LagomApplication(context) with WiredClusterComponents

          val dependency = wire[Dependency]

          val component = wire[ShardedTestComponent.Component].init()

          val service = wire[Service]
        }

        ApplicationLoader.service shouldBe a[Service]
      }
      "minimal with wiring containing circular dependency" in {

        class Service(
                       component1: CircularDependencyTest.ShardedTestComponent1.Wiring,
                       component2: CircularDependencyTest.ShardedTestComponent2.Wiring,
                     ) {
          component1.entityRef("entityId1") ! CircularDependencyTest.ShardedTestComponent1.Command()
          component2.entityRef("entityId2") ! CircularDependencyTest.ShardedTestComponent2.Command()
        }

        object ApplicationLoader {
          lazy val component1: ClusterComponent.ShardedComponent[CircularDependencyTest.ShardedTestComponent1.type] = wire[CircularDependencyTest.ShardedTestComponent1.Component].init()
          lazy val component2: ClusterComponent.ShardedComponent[CircularDependencyTest.ShardedTestComponent2.type] = wire[CircularDependencyTest.ShardedTestComponent2.Component].init()

          val service = wire[Service]
        }

        ApplicationLoader.service shouldBe a[Service]
      }
      "custom EntityId" in {
        object ComponentObject extends ClusterComponent.Sharded with ClusterComponent.SameSerializableCommand {
          case class EntityId(id1: String, id2: String)

          // in case the EntityId is defined outside of the Component you can also override the type and alias it
          //override type EntityId = EntityId

          object EntityId {

            implicit val codec: EntityIdCodec[EntityId] = EntityIdCodec[EntityId](
              entityId => s"${entityId.id1}-${entityId.id2}",
              entityId => entityId.split('-').toList match {
                case id1 :: id2 :: Nil => Success(EntityId(id1, id2))
              },
              entityId => CustomContext(
                "id1" -> entityId.id1,
                "id2" -> entityId.id2
              )
            )
          }

          case class Command()

          object Command {
            implicit val codec: Codec[Command] = deriveCodec
          }

          class Component(dependency: Dependency) extends BaseComponent {
            override val behavior = componentContext =>
              Behaviors.receiveMessage {
                case Command() => Behaviors.same
              }
          }

          override val name = "sharded3"

          override val typeKey = generateTypeKey

          override val commandSerializer = CirceSerializer[Command]()
        }

        new ComponentObject.Component(new Dependency).init()
      }
      "custom json EntityId" in {
        object ComponentObject extends ClusterComponent.Sharded with ClusterComponent.SameSerializableCommand with ClusterComponent.Sharded.JsonEntityId {
          case class EntityId(id1: String, id2: String)

          override implicit val entityIdCirceCodec = deriveCodec

          case class Command()

          object Command {
            implicit val codec: Codec[Command] = deriveCodec
          }

          class Component(dependency: Dependency) extends BaseComponent {
            override val behavior = componentContext =>
              Behaviors.receiveMessage {
                case Command() => Behaviors.same
              }
          }

          override val name = "sharded4"

          override val typeKey = generateTypeKey

          override val commandSerializer = CirceSerializer[Command]()
        }

        new ComponentObject.Component(new Dependency).init()
      }
      "persistence" - {
        "minimal" in {
          object ComponentObject extends ClusterComponent.Sharded.EventSourced with ClusterComponent.SameSerializableCommand with ClusterComponent.Sharded.StringEntityId {
            case class Command()
            implicit val commandCodec: Codec[SerializableCommand] = deriveCodec

            case class Event()
            implicit val eventCodec: Codec[Event] = deriveCodec

            case class State()

            class Component(dependency: Dependency) extends BaseComponent {
              override val behavior = componentContext => EventSourcedBehavior(
                componentContext.persistenceId,
                State(),
                {
                  case (state, command) => Effect.none
                },
                {
                  case (state, event) => state
                })
            }

            override val name = "sharded5"

            override val typeKey = generateTypeKey

            override val commandSerializer = CirceSerializer()

            override val eventSerializer = CirceSerializer()
          }

          new ComponentObject.Component(new Dependency).init()
        }
        "with snapshots" in {
          object ComponentObject extends ClusterComponent.Sharded.EventSourced.WithSnapshots with ClusterComponent.SameSerializableCommand with ClusterComponent.Sharded.StringEntityId {
            case class Command()
            implicit val commandCodec: Codec[SerializableCommand] = deriveCodec

            case class Event()
            implicit val eventCodec: Codec[Event] = deriveCodec

            case class State()
            implicit val stateCodec: Codec[State] = deriveCodec

            class Component(dependency: Dependency) extends BaseComponent {
              override val behavior = componentContext => EventSourcedBehavior(
                componentContext.persistenceId,
                State(),
                {
                  case (state, command) => Effect.none
                },
                {
                  case (state, event) => state
                })
            }

            override val name = "sharded6"

            override val typeKey = generateTypeKey

            override val commandSerializer = CirceSerializer()

            override val eventSerializer = CirceSerializer()
            override val retentionCriteria = RetentionCriteria.snapshotEvery(100, 2)
            override val stateSerializer = CirceSerializer()
          }
        }
        "with projections" in {
          object ComponentObject extends ClusterComponent.Sharded.EventSourced.WithSnapshots with ClusterComponent.SameSerializableCommand with ClusterComponent.Sharded.StringEntityId {
            case class Command()
            implicit val commandCodec: Codec[SerializableCommand] = deriveCodec

            case class Event()
            implicit val eventCodec: Codec[Event] = deriveCodec

            case class State()
            implicit val stateCodec: Codec[State] = deriveCodec

            class Component(dependency: Dependency) extends BaseComponent {
              override val behavior = componentContext => EventSourcedBehavior(
                componentContext.persistenceId,
                State(),
                {
                  case (state, command) => Effect.none
                },
                {
                  case (state, event) => state
                })

              override val projections = Seq(
                ClusterComponent.Projection(
                  "projection",
                  {
                    case (event, projectionContext) => Future.successful(Done)
                  }
                ))
            }

            override val name = "sharded7"

            override val typeKey = generateTypeKey

            override val commandSerializer = CirceSerializer()

            override val eventSerializer = CirceSerializer()
            override val retentionCriteria = RetentionCriteria.snapshotEvery(100, 2)
            override val stateSerializer = CirceSerializer()
          }
        }
      }
    }
  }
}

object ClusterComponentSpec {
  class Dependency

  object SingletonTestComponent extends ClusterComponent.Singleton with ClusterComponent.SameSerializableCommand {
    case class Command()

    object Command {
      implicit val codec: Codec[Command] = deriveCodec
    }

    class Component(dependency: Dependency)(implicit actorSystem: ActorSystem[_]) extends BaseComponent {
      override val behavior = componentContext => Behaviors.receiveMessage {
        case Command() => Behaviors.same
      }
    }

    override val name = "singleton"

    override val commandSerializer = CirceSerializer[Command]()
  }

  object ShardedTestComponent extends ClusterComponent.Sharded with ClusterComponent.SameSerializableCommand with ClusterComponent.Sharded.StringEntityId {
    case class Command()

    object Command {
      implicit val codec: Codec[Command] = deriveCodec
    }

    class Component(dependency: Dependency)(implicit actorSystem: ActorSystem[_]) extends BaseComponent {
      override val behavior = componentContext =>
        Behaviors.receiveMessage {
          case Command() => Behaviors.same
        }
    }

    override val name = "sharded"

    override val typeKey = generateTypeKey

    override val commandSerializer = CirceSerializer[Command]()
  }

  object CircularDependencyTest {
    object ShardedTestComponent1 extends ClusterComponent.Sharded with ClusterComponent.SameSerializableCommand with ClusterComponent.Sharded.StringEntityId {
      case class Command()

      object Command {
        implicit val codec: Codec[Command] = deriveCodec
      }

      // pass circular dependencies by-name =>
      class Component(component2: => ShardedTestComponent2.Wiring)(implicit actorSystem: ActorSystem[_]) extends BaseComponent {
        override val behavior = componentContext =>
          Behaviors.receiveMessage {
            case Command() => Behaviors.same
          }
      }

      override val name = "sharded1"

      override val typeKey = generateTypeKey

      override val commandSerializer = CirceSerializer[Command]()
    }

    object ShardedTestComponent2 extends ClusterComponent.Sharded with ClusterComponent.SameSerializableCommand with ClusterComponent.Sharded.StringEntityId {
      case class Command()

      object Command {
        implicit val codec: Codec[Command] = deriveCodec
      }

      class Component(component1: => ShardedTestComponent1.Wiring)(implicit actorSystem: ActorSystem[_]) extends BaseComponent {
        override val behavior = componentContext =>
          Behaviors.receiveMessage {
            case Command() => Behaviors.same
          }
      }

      override val name = "sharded2"

      override val typeKey = generateTypeKey

      override val commandSerializer = CirceSerializer[Command]()
    }
  }
}

