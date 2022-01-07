package net.sc8s.akka.components.lagom

import com.lightbend.lagom.scaladsl.server.LagomApplication
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

  // for convenience try
  // override val clusterComponents = com.softwaremill.macwire.wireSet
  val clusterComponents: Set[ClusterComponent.Component[_]]

  override def circeSerializerRegistry = super.circeSerializerRegistry ++ new CirceSerializerRegistry {
      override def serializers = clusterComponents.flatMap(_.serializers).toSeq
  }

  // call this at the end to initialize singletons, shards & projections
  final def initComponents() = clusterComponents.foreach(_.delayedInit())

  override def projections: Set[ManagedProjection[_, _]] = super.projections ++ clusterComponents.flatMap(_.managedProjections)
}
