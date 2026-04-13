package net.sc8s.akka.components.persistence.r2dbc.tapir

import akka.actor.typed.ActorSystem
import net.sc8s.akka.components.ClusterComponent
import net.sc8s.akka.components.persistence.r2dbc.common.EntityCleanupActor

trait ClusterComponentsR2dbcPersistenceEndpointsComponents {

  implicit val actorSystemTyped: ActorSystem[_]

  val clusterComponents: Set[ClusterComponent.Component[_]]

  lazy val entityCleanupComponent: EntityCleanupActor.Wiring = EntityCleanupActor.init(new EntityCleanupActor.Component(clusterComponents))

  lazy val clusterComponentsR2dbcPersistenceEndpoints = new ClusterComponentsR2dbcPersistenceEndpoints(clusterComponents, entityCleanupComponent, actorSystemTyped)
}
