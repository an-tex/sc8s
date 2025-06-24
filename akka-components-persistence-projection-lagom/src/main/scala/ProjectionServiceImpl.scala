package net.sc8s.akka.projection.lagom

import akka.actor.typed.ActorSystem
import com.lightbend.lagom.scaladsl.api.ServiceCall
import net.sc8s.akka.components.persistence.projection.ManagedProjection
import net.sc8s.akka.components.persistence.projection.common.ProjectionManagement
import net.sc8s.akka.components.persistence.projection.lagom.api.ProjectionService
import net.sc8s.logstage.elastic.Logging

trait ProjectionServiceImpl extends ProjectionService with Logging {
  val projections: Set[ManagedProjection[_]]

  implicit val actorSystem: ActorSystem[_]

  private[this] lazy val projectionManagement = new ProjectionManagement(projections, actorSystem)

  override def rebuildProjection(projectionName: String) = ServiceCall { _ =>
    projectionManagement.rebuildProjection(projectionName)
  }

  override def pauseProjection(projectionName: String) = ServiceCall { _ =>
    projectionManagement.pauseProjection(projectionName)
  }

  override def resumeProjection(projectionName: String) = ServiceCall { _ =>
    projectionManagement.resumeProjection(projectionName)
  }

  override def projectionStatus(projectionName: String) = ServiceCall { _ =>
    projectionManagement.projectionStatus(projectionName)
  }

  override def projectionsStatus = ServiceCall { _ =>
    projectionManagement.projectionsStatus
  }
}
