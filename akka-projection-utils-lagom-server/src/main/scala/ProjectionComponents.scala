package net.sc8s.akka.projection.lagom

import net.sc8s.akka.projection.ProjectionUtils.ManagedProjection

trait ProjectionComponents {
  def projections: Set[ManagedProjection[_, _]] = Set.empty

  /**
   * don't call this when using ClusterComponents, they're automatically initialized upon component initialization
   */
  def initProjections() = projections.foreach(_.init())
}
