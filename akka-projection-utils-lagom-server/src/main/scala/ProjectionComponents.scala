package net.sc8s.akka.projection.lagom

import com.lightbend.lagom.scaladsl.server.LagomApplication
import net.sc8s.akka.circe.{CirceSerializer, CirceSerializerRegistry}
import net.sc8s.akka.projection.ProjectionUtils.ManagedProjection
import net.sc8s.akka.projection.api.ProjectionService.ProjectionStatus
import net.sc8s.lagom.circe.CirceAkkaSerializationComponents

trait ProjectionComponents extends CirceAkkaSerializationComponents {
  _: LagomApplication =>

  def projections: Set[ManagedProjection[_, _]] = Set.empty

  /**
   * don't call this when using ClusterComponents, they're automatically initialized upon component initialization
   */
  def initProjections() = projections.foreach(_.init())

  override def circeSerializerRegistry = super.circeSerializerRegistry ++ new CirceSerializerRegistry {
    override def serializers = Seq(CirceSerializer[ProjectionStatus]())
  }
}
