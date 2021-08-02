package net.sc8s.akka.projection.lagom.api

import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.transport.Method
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}
import net.sc8s.akka.projection.api.ProjectionService.ProjectionsStatus
import net.sc8s.lagom.circe.JsonMessageSerializer._

trait ProjectionService extends Service {
  val apiPrefix: String

  def rebuildProjection(projectionName: String): ServiceCall[NotUsed, Done]

  def pauseProjection(projectionName: String): ServiceCall[NotUsed, Done]

  def resumeProjection(projectionName: String): ServiceCall[NotUsed, Done]

  def projectionStatus(projectionName: String): ServiceCall[NotUsed, ProjectionsStatus]

  def projectionsStatus: ServiceCall[NotUsed, Seq[ProjectionsStatus]]

  abstract override def descriptor = {
    import Service._
    super.descriptor.addCalls(
      restCall(Method.POST, s"$apiPrefix/projection/:projectionName/rebuild", rebuildProjection _),
      restCall(Method.POST, s"$apiPrefix/projection/:projectionName/pause", pauseProjection _),
      restCall(Method.POST, s"$apiPrefix/projection/:projectionName/resume", resumeProjection _),
      restCall(Method.GET, s"$apiPrefix/projection/:projectionName", projectionStatus _),
      restCall(Method.GET, s"$apiPrefix/projection", projectionsStatus _)
    )
  }
}
