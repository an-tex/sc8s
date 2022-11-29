package net.sc8s.akka.components.persistence.projection.r2dbc

import akka.Done
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import com.typesafe.config.ConfigFactory
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import net.sc8s.akka.circe.CirceSerializer
import net.sc8s.akka.components.ClusterComponent
import net.sc8s.akka.components.persistence.projection.r2dbc.R2DbcProjectionSpec.{Dependency, randomName}
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future
import scala.util.Random

/*
This spec is only meant to illustrate the usage of CassandraProjections
 */
class R2DbcProjectionSpec extends ScalaTestWithActorTestKit(ConfigFactory.parseString(
  """
    |akka.actor.provider = cluster
    |akka.remote.artery.canonical.port = 0
    |""".stripMargin)) with AnyFreeSpecLike with Matchers {

  "ClusterComponents" - {
    // TODO https://discuss.lightbend.com/t/r2dbc-eventsbyslices-query-for-projections-with-cluster-singleton-without-entitytype/10089
    //"Singleton with projections" in {
    //  object ComponentObject extends ClusterComponent.Singleton.EventSourced with ClusterComponent.SameSerializableCommand {
    //    case class Command()
    //    implicit val commandCodec: Codec[SerializableCommand] = deriveCodec
    //
    //    case class Event()
    //    implicit val eventCodec: Codec[Event] = deriveCodec
    //
    //    case class State()
    //
    //    class Component(dependency: Dependency) extends BaseComponent with R2dbcProjection {
    //      override val behavior = componentContext => EventSourcedBehavior(
    //        componentContext.persistenceId,
    //        State(),
    //        {
    //          case (state, command) => Effect.none
    //        },
    //        {
    //          case (state, event) => state
    //        })
    //
    //      override val projections = Set(
    //        ClusterComponent.Projection(
    //          "projectionSingleton",
    //          {
    //            case (event, projectionContext) => Future.successful(Done)
    //          }
    //        ))
    //    }
    //
    //    override val name = "singleton"
    //
    //    override val commandSerializer = CirceSerializer()
    //
    //    override val eventSerializer = CirceSerializer()
    //  }
    //
    //  ComponentObject.init(new ComponentObject.Component(new Dependency)).delayedInit()
    //}
    "Sharded with projections" in {
      object ComponentObject extends ClusterComponent.Sharded.EventSourced.WithSnapshots with ClusterComponent.SameSerializableCommand with ClusterComponent.Sharded.StringEntityId {
        case class Command()
        implicit val commandCodec: Codec[SerializableCommand] = deriveCodec

        case class Event()
        implicit val eventCodec: Codec[Event] = deriveCodec

        case class State()
        implicit val stateCodec: Codec[State] = deriveCodec

        class Component(dependency: Dependency) extends BaseComponent with R2dbcProjection {
          override val behavior = componentContext => EventSourcedBehavior(
            componentContext.persistenceId,
            State(),
            {
              case (state, command) => Effect.none
            },
            {
              case (state, event) => state
            })

          override val projections = Set(
            ClusterComponent.Projection(
              "projectionSharded",
              {
                case (event, projectionContext) => Future.successful(Done)
              }
            ))

          override val retentionCriteria = RetentionCriteria.snapshotEvery(100, 2)
        }

        override val name = randomName

        override val commandSerializer = CirceSerializer()
        override val eventSerializer = CirceSerializer()
        override val stateSerializer = CirceSerializer()
      }
      ComponentObject.init(new ComponentObject.Component(new Dependency)).delayedInit()
    }
  }
}

object R2DbcProjectionSpec {
  class Dependency

  def randomName = s"sharded-${Random.alphanumeric.take(8).mkString}"
}