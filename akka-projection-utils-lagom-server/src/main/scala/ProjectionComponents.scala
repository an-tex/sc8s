package net.sc8s.akka.projection.lagom

import net.sc8s.akka.projection.ProjectionUtils.ManagedProjection

trait ProjectionComponents {
  val projections: Set[ManagedProjection[_, _]]

  def initProjections() = projections.foreach(_.init())
}
