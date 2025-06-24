package net.sc8s.akka.projection.lagom

import akka.actor.typed.ActorSystem
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.api.transport.BadRequest
import net.sc8s.akka.components.persistence.projection.ManagedProjection
import net.sc8s.akka.components.persistence.projection.common.ProjectionManagement
import net.sc8s.akka.components.persistence.projection.lagom.api.ProjectionService
import net.sc8s.logstage.elastic.Logging

import scala.concurrent.Future

trait ProjectionServiceImpl extends ProjectionService with Logging {
  val projections: Set[ManagedProjection[_]]

  implicit val actorSystem: ActorSystem[_]

  import actorSystem.executionContext

  private[this] lazy val projectionManagement = new ProjectionManagement(projections, actorSystem)

  override def rebuildProjection(projectionName: String) = ServiceCall { _ =>
    orBadRequest(projectionManagement.rebuildProjection(projectionName))
  }

  override def pauseProjection(projectionName: String) = ServiceCall { _ =>
    orBadRequest(projectionManagement.pauseProjection(projectionName))
  }

  override def resumeProjection(projectionName: String) = ServiceCall { _ =>
    orBadRequest(projectionManagement.resumeProjection(projectionName))
  }

  override def projectionStatus(projectionName: String) = ServiceCall { _ =>
    orBadRequest(projectionManagement.projectionStatus(projectionName))
  }

  override def projectionsStatus = ServiceCall { _ =>
    projectionManagement.projectionsStatus
  }

  private[this] def orBadRequest[T](eventualEither: Future[Either[String, T]]) =
    eventualEither.map(_.fold(
      message => throw BadRequest(message),
      identity
    ))
}
