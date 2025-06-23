package net.sc8s.akka.components.persistence.r2dbc.lagom

import akka.actor.typed.ActorSystem
import com.lightbend.lagom.scaladsl.api.ServiceCall
import net.sc8s.akka.components.ClusterComponent
import net.sc8s.akka.components.persistence.r2dbc.common.ClusterComponentsR2dbcPersistenceManagement
import net.sc8s.akka.components.persistence.r2dbc.lagom.api.ClusterComponentsR2dbcPersistenceService
import net.sc8s.logstage.elastic.Logging

trait ClusterComponentsR2dbcPersistenceServiceImpl extends ClusterComponentsR2dbcPersistenceService with Logging {
  val clusterComponents: Set[ClusterComponent.Component[_]]

  implicit val actorSystem: ActorSystem[_]

  private[this] lazy val clusterComponentsR2dbcPersistenceManagement = new ClusterComponentsR2dbcPersistenceManagement(clusterComponents, actorSystem)

  override def deleteSingletonEntity(name: String) = ServiceCall { _ =>
    clusterComponentsR2dbcPersistenceManagement.deleteSingletonEntity(name)
  }

  override def deleteShardedEntities(name: String) = ServiceCall { _ =>
    clusterComponentsR2dbcPersistenceManagement.deleteShardedEntities(name)
  }
}
