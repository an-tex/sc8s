package net.sc8s.akka.components.persistence.projection.common

import akka.actor.typed.ActorSystem
import cats.implicits.{catsStdInstancesForFuture, toTraverseOps}
import cats.instances.list._
import net.sc8s.akka.components.persistence.projection.ManagedProjection
import net.sc8s.akka.components.persistence.projection.api.ProjectionService.ProjectionsStatus
import net.sc8s.logstage.elastic.Logging

import scala.concurrent.Future

class ProjectionManagement(
                            val projections: Set[ManagedProjection[_]],
                            implicit val actorSystem: ActorSystem[_],
                          ) extends Logging {

  import actorSystem.executionContext

  def rebuildProjection(projectionName: String) =
    projectionOperation(projectionName, _.rebuild())

  def pauseProjection(projectionName: String) =
    projectionOperation(projectionName, _.pause())

  def resumeProjection(projectionName: String) =
    projectionOperation(projectionName, _.resume())

  def projectionStatus(projectionName: String) =
    projectionOperation(projectionName, _.status)

  def projectionsStatus: Future[List[ProjectionsStatus]] =
    projections.map(_.status).toList.sequence

  private def projectionOperation[T](projectionName: String, operation: ManagedProjection[_] => Future[T]) =
    projections.find(_.projectionName == projectionName) match {
      case Some(value) => operation(value).map(Right(_))
      case None => Future.successful(Left(s"projectionName=$projectionName not found, validProjectionNames=${projections.map(_.projectionName).mkString(";")}"))
    }
}