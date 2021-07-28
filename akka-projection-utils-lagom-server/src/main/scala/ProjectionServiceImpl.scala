package net.sc8s.akka.projection.lagom

import api.ProjectionService

import akka.actor.typed.ActorSystem
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.api.transport.BadRequest
import net.sc8s.akka.projection.ProjectionUtils.ManagedProjection

import scala.concurrent.{ExecutionContext, Future}

trait ProjectionServiceImpl extends ProjectionService {
  val projections: Set[ManagedProjection[_, _]]

  implicit val typedSystem: ActorSystem[_]
  implicit val executionContext: ExecutionContext

  override def rebuildProjection(projectionName: String) = ServiceCall { _ =>
    projections.find(_.projectionName == projectionName) match {
      case Some(projection) =>
        projection.rebuildProjections()
      case None =>
        Future.failed(BadRequest(s"projectionName=$projectionName not found, validProjectionNames=${projections.map(_.projectionName).mkString(";")}"))
    }
  }
}
