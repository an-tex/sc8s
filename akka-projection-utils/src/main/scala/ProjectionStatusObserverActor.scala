package net.sc8s.akka.projection

import ProjectionStatusObserverActor.Command
import ProjectionStatusObserverActor.Command.GetStatus
import api.ProjectionService.ProjectionStatus

import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.adapter.{ClassicActorSystemOps, TypedActorSystemOps}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.persistence.query.Offset
import akka.projection.eventsourced.EventEnvelope
import akka.projection.{HandlerRecoveryStrategy, ProjectionId, StatusObserver}
import io.circe._
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.syntax.EncoderOps
import izumi.logstage.api.Log.CustomContext
import net.sc8s.akka.circe.AkkaRefCodecs._
import net.sc8s.akka.circe.CirceSerializer
import net.sc8s.circe.CodecConfiguration._
import net.sc8s.logstage.elastic.Logging

class ProjectionStatusObserverActor private(actorContext: ActorContext[Command], projectionId: ProjectionId) extends Logging {

  log.info(s"${"initializing" -> "tag"}")

  def behavior(projectionStatus: ProjectionStatus): Behaviors.Receive[Command] = Behaviors.receiveMessagePartial {
    case Command.Started =>
      log.info(s"${"started" -> "tag"}")
      behavior(ProjectionStatus.Running(None, Nil))

    case Command.Failed(cause) =>
      log.error(s"${"failed" -> "tag"} due to $cause")
      behavior(ProjectionStatus.Failed(cause.toString))

    case Command.GetStatus(replyTo) =>
      replyTo ! GetStatus.Response(projectionId, projectionStatus)
      Behaviors.same

    case Command.Stopped =>
      log.info(s"${"stopped" -> "tag"}")
      behavior(ProjectionStatus.Stopped(projectionStatus))

    case Command.OffsetProgress(sequenceNr, offset) =>
      behavior(projectionStatus match {
        case ProjectionStatus.Running(_, errors) =>
          log.debug(s"${"progress" -> "tag"} up to $sequenceNr at $offset")
          ProjectionStatus.Running(Some(sequenceNr), errors)
        case invalidProjectionStatus =>
          log.error(s"${"gotOffsetWhileNotRunning" -> "tag"} $invalidProjectionStatus")
          invalidProjectionStatus
      })

    case Command.Error(sequenceNr, cause, recoveryStrategy) =>
      behavior(projectionStatus match {
        case ProjectionStatus.Running(_sequenceNr, errors) =>
          log.error(s"${"error" -> "tag"} at $sequenceNr due to $cause with $recoveryStrategy")
          ProjectionStatus.Running(_sequenceNr, (errors :+ (sequenceNr -> cause.toString)).takeRight(10))
        case invalidProjectionStatus =>
          log.error(s"${"gotErrorWhileNotRunning" -> "tag"} $invalidProjectionStatus")
          invalidProjectionStatus
      })
  }

  override protected lazy val logContext = CustomContext(
    "projectionName" -> projectionId.name,
    "projectionKey" -> projectionId.key,
    "projectionId" -> projectionId.id,
  )
}

object ProjectionStatusObserverActor {

  val serviceKey = ServiceKey[Command]("projectionStatusObserver")

  sealed trait Command
  sealed trait SerializableCommand extends Command
  object Command {
    private[ProjectionStatusObserverActor] case object Started extends Command
    private[ProjectionStatusObserverActor] case class Failed(cause: Throwable) extends Command
    private[ProjectionStatusObserverActor] case object Stopped extends Command
    private[ProjectionStatusObserverActor] case class OffsetProgress(sequenceNr: Long, offset: Offset) extends Command
    private[ProjectionStatusObserverActor] case class Error(sequenceNr: Long, cause: Throwable, recoveryStrategy: HandlerRecoveryStrategy) extends Command

    case class GetStatus(replyTo: ActorRef[GetStatus.Response]) extends SerializableCommand
    object GetStatus {
      case class Response(projectionId: ProjectionId, projectionStatus: ProjectionStatus) extends SerializableResponse
    }

    implicit val codec: Codec[SerializableCommand] = {
      import SerializableResponse.projectionIdCodec
      import io.circe.generic.extras.auto._
      deriveConfiguredCodec[SerializableCommand]
    }
  }

  sealed trait SerializableResponse
  object SerializableResponse {
    implicit val projectionIdCodec: Codec[ProjectionId] = Codec.from(
      (c: HCursor) => for {
        name <- c.downField("name").as[String]
        key <- c.downField("key").as[String]
      } yield ProjectionId.of(name, key),
      (projectionId: ProjectionId) => Json.obj(
        "name" -> projectionId.name.asJson,
        "key" -> projectionId.key.asJson,
      )
    )

    implicit val codec: Codec[SerializableResponse] = deriveConfiguredCodec[SerializableResponse]
  }

  val serializers = Seq(
    CirceSerializer[SerializableCommand](),
    CirceSerializer[SerializableResponse](),
  )

  def apply[Event](projectionId: ProjectionId)(implicit actorSystem: ActorSystem[_]): ProjectionStatusObserver[Event] =
    new ProjectionStatusObserver[Event] {
      override val actorRef = actorSystem.toClassic.spawn(Behaviors.setup[Command](
        new ProjectionStatusObserverActor(_, projectionId).behavior(ProjectionStatus.Initializing)
      ), s"projectionStatusObserver-${projectionId.id}")

      override val statusObserver = new StatusObserver[EventEnvelope[Event]] {
        override def started(projectionId: ProjectionId) =
          actorRef ! Command.Started

        override def failed(projectionId: ProjectionId, cause: Throwable) =
          actorRef ! Command.Failed(cause)

        override def stopped(projectionId: ProjectionId) =
          actorRef ! Command.Stopped

        override def beforeProcess(projectionId: ProjectionId, envelope: EventEnvelope[Event]) = ()

        override def afterProcess(projectionId: ProjectionId, envelope: EventEnvelope[Event]) = ()

        override def offsetProgress(projectionId: ProjectionId, env: EventEnvelope[Event]) =
          actorRef ! Command.OffsetProgress(env.sequenceNr, env.offset)

        override def error(projectionId: ProjectionId, env: EventEnvelope[Event], cause: Throwable, recoveryStrategy: HandlerRecoveryStrategy) =
          actorRef ! Command.Error(env.sequenceNr, cause, recoveryStrategy)
      }
    }
}

trait ProjectionStatusObserver[Event] {
  val actorRef: ActorRef[ProjectionStatusObserverActor.SerializableCommand]
  val statusObserver: StatusObserver[EventEnvelope[Event]]
}