package net.sc8s.schevo.circe.example.akka

import PersistentBehavior.Event.Added.AddedV1

import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import io.circe.Codec
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import net.sc8s.akka.circe.CirceSerializer
import net.sc8s.akka.circe.implicits._
import net.sc8s.schevo.circe.SchevoCirce

import scala.annotation.nowarn

object PersistentBehavior {
  def apply(persistenceId: PersistenceId) = EventSourcedBehavior[Command, Event, State](
    persistenceId,
    State.Empty,
    // even though some Command, Event and State case class are versioned, there's are no explicit version definitions in your code (except in the model it self). You only use the Latest trait/companion object
    {
      // you can either use the Latest trait
      case (_, latest: Command.Add.Latest) =>
        Effect.persist(Event.Added.Latest(latest.data, latest.clearBeforeAdd))
    },
    {
      // or explicitly defined unapply
      case (State.Empty, Event.Added.Latest(data, _)) =>
        // and apply
        State.NonEmpty.Latest(Seq(data), 1)

      case (state@State.NonEmpty.Latest(history, count), Event.Added.Latest(data, clearBeforeAdd)) =>
        val existingHistory = if (clearBeforeAdd) Nil else history
        state.caseClass.copy(existingHistory :+ data, count + 1)

      // there's one downside: you'll get `match may not be exhaustive warning`
    }
  )

  sealed trait Command
  object Command {
    // commands are special as they are not de/serialized within the same JVM. so you're safer off moving (non refactor move) the existing case class to a V0 and adding a manifestRename to the CirceSerializer. This way you're forced at compile time to use the Latest version. Old versions (e.g. coming from other JVMs during rolling deployment) are still evolved as they are de/serialized. we keep it here to test the evolution
    case class Add(data: String) extends Command

    object Add extends SchevoCirce {
      sealed trait Latest extends LatestT with Version {
        val data: String
        val clearBeforeAdd: Boolean
      }

      // separate apply/unapply allow having no version definitions in the code using the model
      object Latest {
        def unapply(addV1: AddV1) = AddV1.unapply(addV1)

        def apply(data: String, clearBeforeAdd: Boolean) = AddV1(data, clearBeforeAdd)
      }

      override type LatestCaseClass = AddV1

      case class AddV1(data: String, clearBeforeAdd: Boolean) extends Latest {
        override def caseClass = this
      }

      case class AddV0(data: String) extends Command with Add.Version {
        override def evolve = Add.AddV1(data, clearBeforeAdd = false)
      }

      sealed trait Version extends Command with VersionT
    }

    case object Clear extends Command

    implicit val codec: Codec[Command] = SchevoCirce.evolvingCodec()(deriveConfiguredCodec)
  }

  sealed trait Event
  object Event {
    // Events and State are always de/serialized so it's fine to keep the original case class, just add a `with XX.Version` and override the evolve function to evolve to V1
    @deprecated("use apply in Added companion object")
    case class Added(data: String) extends Event with Added.Version {
      override def evolve = AddedV1(data, clearBeforeAdd = false).evolve
    }

    object Added extends SchevoCirce {
      sealed trait Latest extends LatestT with Version {
        val data: String
        val clearBeforeAdd: Boolean
      }

      object Latest {
        def unapply(addedV1: AddedV1) = AddedV1.unapply(addedV1)

        def apply(data: String, clearBeforeAdd: Boolean) = AddedV1(data, clearBeforeAdd)
      }

      override type LatestCaseClass = AddedV1

      case class AddedV1(data: String, clearBeforeAdd: Boolean) extends Latest {
        override def caseClass = this
      }

      sealed trait Version extends Event with VersionT
    }

    case object Cleared extends Event

    implicit val codec: Codec[Event] = SchevoCirce.evolvingCodec[Event]()(deriveConfiguredCodec)
  }

  sealed trait State
  object State {
    case object Empty extends State

    @deprecated("NonEmpty.Latest")
    case class NonEmpty(history: Seq[String]) extends State with NonEmpty.Version {
      override def evolve = NonEmpty.NonEmptyV1(history, history.length)
    }

    object NonEmpty extends SchevoCirce {
      sealed trait Latest extends LatestT with Version {
        val history: Seq[String]
        val addCount: Int
      }

      object Latest {
        def apply(history: Seq[String], addCount: Int) = NonEmptyV1(history, addCount)

        def unapply(nonEmptyV1: NonEmptyV1) = NonEmptyV1.unapply(nonEmptyV1)
      }

      override type LatestCaseClass = NonEmptyV1

      case class NonEmptyV1(history: Seq[String], addCount: Int) extends Latest {
        override def caseClass = this
      }

      sealed trait Version extends State with VersionT
    }

    implicit val codec: Codec[State] = SchevoCirce.evolvingCodec[State]()(deriveConfiguredCodec)
  }

  val serializers = Seq(
    CirceSerializer[Command](manifestRenames = Map(
      // as usually it's recommended to delete to original case class for commands, you need to explicitly tell the serializer about it's "rename"
      "net.sc8s.schevo.circe.example.akka.PersistentBehavior$Command$Add" -> classOf[Command.Add.AddV0]
    )),
    CirceSerializer[Event](),
    CirceSerializer[State]()
  )
}
