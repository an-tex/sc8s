package net.sc8s.akka.components.persistence.projection.lagom

import com.lightbend.lagom.scaladsl.server.LagomApplication
import net.sc8s.akka.circe.{CirceSerializer, CirceSerializerRegistry}
import net.sc8s.akka.components.persistence.projection.ManagedProjection
import net.sc8s.akka.components.persistence.projection.api.ProjectionService.ProjectionStatus
import net.sc8s.lagom.circe.CirceAkkaSerializationComponents

trait ProjectionComponents extends CirceAkkaSerializationComponents {
  _: LagomApplication =>

  def projections: Set[ManagedProjection[_]] = Set.empty

  override def circeSerializerRegistry = super.circeSerializerRegistry ++ new CirceSerializerRegistry {
    override def serializers = Seq(CirceSerializer[ProjectionStatus]())
  }
}
