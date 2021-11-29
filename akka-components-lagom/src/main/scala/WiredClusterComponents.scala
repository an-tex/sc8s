package net.sc8s.akka.components.lagom

import com.lightbend.lagom.scaladsl.server.LagomApplication
import com.softwaremill.macwire.wireSet
import net.sc8s.akka.circe.CirceSerializerRegistry
import net.sc8s.akka.components.ClusterComponent
import net.sc8s.akka.projection.ProjectionUtils.ManagedProjection
import net.sc8s.akka.projection.lagom.ProjectionComponents
import net.sc8s.lagom.circe.CirceAkkaSerializationComponents

/*
ClusterComponents is taken by com.lightbend.lagom.scaladsl.cluster.ClusterComponents
 */
trait WiredClusterComponents extends CirceAkkaSerializationComponents with ProjectionComponents {
  _: LagomApplication =>

  private lazy val components: Set[ClusterComponent.ComponentT[_, _, _]] = wireSet[ClusterComponent.Component[_]].map(_.component)

  override def circeSerializerRegistry = super.circeSerializerRegistry ++ new CirceSerializerRegistry {
    override def serializers = {
      components.flatMap(_.serializers).toSeq
    }
  }

  override def projections: Set[ManagedProjection[_, _]] = super.projections ++ components.flatMap(_.managedProjections)
}
