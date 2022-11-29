package net.sc8s.akka.components.persistence.projection

import akka.Done
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.projection.scaladsl.ProjectionManagement
import akka.projection.{Projection, ProjectionBehavior, ProjectionId}
import cats.implicits.{catsStdInstancesForFuture, toTraverseOps}
import cats.instances.list._
import izumi.logstage.api.Log.CustomContext
import net.sc8s.akka.components.persistence.projection.api.ProjectionService.ProjectionsStatus
import net.sc8s.logstage.elastic.Logging

import scala.concurrent.Future

case class EventEnvelope[Event, EntityId](entityId: EntityId, event: Event, timestamp: Long)

abstract class ManagedProjection[Envelope](
                                            val projectionName: String,
                                            projectionIds: Seq[ProjectionId],
                                            numberOfInstances: Int,
                                            projectionStatusObserver: ProjectionStatusObserver[Envelope],
                                            implicit val actorSystem: ActorSystem[_],
                                          ) extends Logging {

  import actorSystem.executionContext

  def projectionFactory(i: Int): Projection[Envelope]

  private lazy val shardedDaemonProcess = ShardedDaemonProcess(actorSystem)

  final def init(shardedDaemonProcessName: String = s"$projectionName-projection") = {
    log.info(s"${"initializingProjection" -> "tag"} $projectionName with ${projectionIds.map(_.id) -> "projectionIds"}")
    shardedDaemonProcess.init[ProjectionBehavior.Command](
      shardedDaemonProcessName,
      numberOfInstances,
      i => ProjectionBehavior(projectionFactory(i).withStatusObserver(projectionStatusObserver.statusObserver)),
      ProjectionBehavior.Stop
    )
  }

  final def rebuild(): Future[Done] = operation("rebuild", _.clearOffset)

  final def pause(): Future[Done] = operation("pause", _.pause)

  final def resume(): Future[Done] = operation("resume", _.resume)

  private def operation(tagPrefix: String, operation: ProjectionManagement => ProjectionId => Future[Done]) = {
    log.info(s"${tagPrefix + "Projections" -> "tag"}")
    projectionIds
      .map(operation(ProjectionManagement(actorSystem)))
      .toList
      .sequence
      .map(_ => Done)
  }

  final def status = {
    projectionIds
      .map(projectionId => projectionStatusObserver.status(projectionId).map(projectionId.id -> _))
      .toList
      .sequence
      .map(_.toMap.collect { case (key, Some(value)) => key -> value })
      .map(
        ProjectionsStatus(
          projectionName,
          _
        )
      )
  }

  override protected lazy val logContext = CustomContext(
    "projectionIds" -> projectionIds.map(_.id)
  )
}
