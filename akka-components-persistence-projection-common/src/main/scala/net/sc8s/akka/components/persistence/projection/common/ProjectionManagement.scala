package net.sc8s.akka.components.persistence.projection.common

import akka.Done
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

  def rebuildProjection(projectionName: String): Future[Done] = {
    findProjection(projectionName).rebuild()
  }

  def pauseProjection(projectionName: String): Future[Done] = {
    findProjection(projectionName).pause()
  }

  def resumeProjection(projectionName: String): Future[Done] = {
    findProjection(projectionName).resume()
  }

  def projectionStatus(projectionName: String): Future[ProjectionsStatus] = {
    findProjection(projectionName).status
  }

  def projectionsStatus: Future[List[ProjectionsStatus]] = {
    projections.map(_.status).toList.sequence
  }

  private def findProjection(projectionName: String): ManagedProjection[_] = {
    projections.find(_.projectionName == projectionName).getOrElse {
      throw new IllegalArgumentException(s"projectionName=$projectionName not found, validProjectionNames=${projections.map(_.projectionName).mkString(";")}")
    }
  }
}