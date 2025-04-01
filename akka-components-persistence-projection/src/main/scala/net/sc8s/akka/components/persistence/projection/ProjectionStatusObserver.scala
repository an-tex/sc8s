package net.sc8s.akka.components.persistence.projection

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import akka.cluster.ddata.typed.scaladsl.Replicator.{GetResponse, UpdateResponse}
import akka.cluster.ddata.typed.scaladsl.{DistributedData, Replicator}
import akka.cluster.ddata.{LWWMap, LWWMapKey}
import akka.persistence.query.Offset
import akka.projection.{HandlerRecoveryStrategy, ProjectionId, StatusObserver}
import izumi.logstage.api.IzLogger
import izumi.logstage.api.Log.CustomContext
import net.sc8s.akka.circe.AkkaRefCodecs._
import net.sc8s.akka.circe.CirceSerializer
import net.sc8s.akka.components.persistence.projection.api.ProjectionService.ProjectionStatus
import net.sc8s.circe.CodecConfiguration._
import net.sc8s.logstage.elastic.Logging

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

abstract class ProjectionStatusObserver[Envelope](implicit actorSystem: ActorSystem[_]) extends Logging {

  def extractSequenceNr(envelope: Envelope): Long

  def extractOffset(envelope: Envelope): Offset

  import actorSystem.executionContext

  private val distributedData = DistributedData(actorSystem)

  private implicit val selfUniqueAddress = distributedData.selfUniqueAddress

  private val key = LWWMapKey[ProjectionId, ProjectionStatus]("projectionsStatus")

  def statusObserver[Event] = new StatusObserver[Envelope] with Logging {
    private def withLoggingContext(projectionId: ProjectionId)(body: IzLogger => Unit): Unit = {
      val loggerWithCustomContext = log.withCustomContext(logContext + CustomContext(
        "projectionName" -> projectionId.name,
        "projectionKey" -> projectionId.key,
        "projectionId" -> projectionId.id,
      ))

      body(loggerWithCustomContext)
    }

    override def started(projectionId: ProjectionId) = withLoggingContext(projectionId) { log =>
      log.info(s"${"started" -> "tag"}")
      updateProjectionStatus(projectionId, projectionStatus => ProjectionStatus.Running(None, projectionStatus.last10Errors))
    }

    override def failed(projectionId: ProjectionId, cause: Throwable) = withLoggingContext(projectionId) { log =>
      log.error(s"${"failed" -> "tag"} due to $cause")
      updateProjectionStatus(projectionId, projectionStatus => ProjectionStatus.Failed(cause.toString, projectionStatus.last10Errors))
    }

    override def stopped(projectionId: ProjectionId) = withLoggingContext(projectionId) { log =>
      log.info(s"${"stopped" -> "tag"}")
      updateProjectionStatus(projectionId, projectionStatus => ProjectionStatus.Stopped(projectionStatus, projectionStatus.last10Errors))
    }

    override def beforeProcess(projectionId: ProjectionId, envelope: Envelope) = ()

    override def afterProcess(projectionId: ProjectionId, envelope: Envelope) = ()

    override def offsetProgress(projectionId: ProjectionId, env: Envelope) = withLoggingContext(projectionId) { log =>
      updateProjectionStatus(projectionId, {
        case ProjectionStatus.Running(_, errors) =>
          log.debug(s"${"progress" -> "tag"} up to ${extractSequenceNr(env) -> "sequenceNr"} at ${extractOffset(env) -> "offset"}")
          ProjectionStatus.Running(Some(extractSequenceNr(env)), errors)
        case invalidProjectionStatus =>
          // progress could still arrive when already in stopped or failed state
          invalidProjectionStatus
      })
    }

    override def error(
                        projectionId: ProjectionId,
                        env: Envelope,
                        cause: Throwable,
                        recoveryStrategy: HandlerRecoveryStrategy
                      ) = withLoggingContext(projectionId) { log =>
      updateProjectionStatus(projectionId, {
        case ProjectionStatus.Running(_sequenceNr, errors) =>
          log.error(s"${"error" -> "tag"} at ${extractSequenceNr(env) -> "sequenceNr"} due to $cause with $recoveryStrategy")
          ProjectionStatus.Running(_sequenceNr, (errors :+ (extractSequenceNr(env) -> cause.toString)).takeRight(10))
        case ProjectionStatus.Failed(_, last10Errors) =>
          log.error(s"${"error" -> "tag"} at ${extractSequenceNr(env) -> "sequenceNr"} due to $cause with $recoveryStrategy")
          ProjectionStatus.Failed(cause.toString, (last10Errors :+ (extractSequenceNr(env) -> cause.toString)).takeRight(10))
        case invalidProjectionStatus =>
          log.error(s"${"gotErrorWhileNotRunning" -> "tag"} $invalidProjectionStatus")
          invalidProjectionStatus
      })
    }

    private def updateProjectionStatus(projectionId: ProjectionId, modify: ProjectionStatus => ProjectionStatus) = {
      distributedData.replicator.ask[UpdateResponse[LWWMap[ProjectionId, ProjectionStatus]]](Replicator.Update[LWWMap[ProjectionId, ProjectionStatus]](
        key,
        LWWMap.empty[ProjectionId, ProjectionStatus],
        Replicator.WriteLocal,
        _
      )(currentMap =>
        currentMap :+ projectionId -> modify(currentMap.get(projectionId).getOrElse(ProjectionStatus.Initializing))
      ))(3.seconds, implicitly)
    }
  }

  def status(projectionId: ProjectionId): Future[Option[ProjectionStatus]] = getMap.map(_.get(projectionId))

  def statusAll: Future[Map[ProjectionId, ProjectionStatus]] = getMap.map(_.entries)

  private def getMap: Future[LWWMap[ProjectionId, ProjectionStatus]] = distributedData
    .replicator
    .ask[GetResponse[LWWMap[ProjectionId, ProjectionStatus]]](Replicator.Get(key, Replicator.ReadMajority(10.seconds), _))(13.seconds, implicitly)
    .map {
      case response@Replicator.GetSuccess(`key`) =>
        response.get(`key`)
      case response =>
        log.error(s"${"fetchingStatusFailed" -> "tag"} got $response")
        throw new Exception(s"tag=fetchingStatusFailed got response=$response")
    }
}
