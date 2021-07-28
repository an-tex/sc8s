package net.sc8s.akka.projection.lagom.api

import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.transport.Method
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}

trait ProjectionService {
  def rebuildProjection(projectionName: String): ServiceCall[NotUsed, Done]

  def projectionServiceCalls(apiPrefix: String) = {
    import Service._
    Seq(
      restCall(Method.POST, s"$apiPrefix/projection/:projectionName/rebuild", rebuildProjection _),
    )
  }
}