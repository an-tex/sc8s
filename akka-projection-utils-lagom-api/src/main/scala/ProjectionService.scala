package net.sc8s.akka.projection.lagom.api

import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.transport.Method
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}

trait ProjectionService extends Service {
  val apiPrefix: String

  def rebuildProjection(projectionName: String): ServiceCall[NotUsed, Done]

  abstract override def descriptor = super.descriptor.addCalls({
    import Service._
    restCall(Method.POST, s"$apiPrefix/projection/:projectionName/rebuild", rebuildProjection _)
  })
}
