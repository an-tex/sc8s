package net.sc8s.akka.projection.lagom

import api.ProjectionService

import akka.NotUsed
import akka.actor.typed.ActorSystem
import cats.implicits.{catsStdInstancesForFuture, toTraverseOps}
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.api.transport.BadRequest
import net.sc8s.akka.projection.ProjectionUtils.ManagedProjection

import scala.concurrent.Future

trait ProjectionServiceImpl extends ProjectionService {
  val projections: Set[ManagedProjection[_, _]]

  implicit val actorSystem: ActorSystem[_]

  import actorSystem.executionContext

  override def rebuildProjection(projectionName: String) = projectionOperation(projectionName, _.rebuild())

  override def pauseProjection(projectionName: String) = projectionOperation(projectionName, _.pause())

  override def resumeProjection(projectionName: String) = projectionOperation(projectionName, _.resume())

  override def projectionStatus(projectionName: String) = projectionOperation(projectionName, _.status)

  override def projectionsStatus = ServiceCall(_ => projections.map(_.status).toList.sequence.map(_.toSeq))

  private def projectionOperation[T](projectionName: String, operation: ManagedProjection[_, _] => Future[T]) = ServiceCall[NotUsed, T] { _ =>
    projections.find(_.projectionName == projectionName) match {
      case Some(projection) =>
        operation(projection)
      case None =>
        Future.failed(BadRequest(s"projectionName=$projectionName not found, validProjectionNames=${projections.map(_.projectionName).mkString(";")}"))
    }
  }
}
