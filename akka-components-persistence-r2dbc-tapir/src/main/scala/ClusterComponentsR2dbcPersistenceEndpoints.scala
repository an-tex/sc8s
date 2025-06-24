package net.sc8s.akka.components.persistence.r2dbc.tapir

import akka.actor.typed.ActorSystem
import cats.implicits.catsSyntaxEitherId
import net.sc8s.akka.components.ClusterComponent
import net.sc8s.akka.components.persistence.r2dbc.common.ClusterComponentsR2dbcPersistenceManagement
import sttp.tapir.EndpointIO.Example
import sttp.tapir._
import sttp.tapir.server.ServerEndpoint

import scala.concurrent.Future

class ClusterComponentsR2dbcPersistenceEndpoints(
                                                  clusterComponents: Set[ClusterComponent.Component[_]],
                                                  actorSystem: ActorSystem[_],
                                                ) {

  private[this] lazy val clusterComponentsR2dbcPersistenceManagement = new ClusterComponentsR2dbcPersistenceManagement(clusterComponents, actorSystem)

  import actorSystem.executionContext

  private[this] val deleteSingletonEntity =
    endpoint
      .delete
      .in("entity" / "singleton" / path[String]("name").examples(
        clusterComponentsR2dbcPersistenceManagement.singletonEntityPersistenceIdsByName.keys.map(name => Example.of(name, Some(name))).toList)
      )

  private[this] val deleteShardedEntities =
    endpoint
      .delete
      .in("entity" / "sharded" / path[String]("name").examples(
        clusterComponentsR2dbcPersistenceManagement.shardedEntityPersistenceIdsByTypeKey.keys.map(name => Example.of(name, Some(name))).toList
      ))

  val endpoints: Seq[Endpoint[_, _, _, _, _]] = Seq(
    deleteSingletonEntity,
    deleteShardedEntities,
  )

  val serverEndpoints: Seq[ServerEndpoint[Any, Future]] = Seq(
    deleteSingletonEntity.serverLogic[Future](clusterComponentsR2dbcPersistenceManagement.deleteSingletonEntity(_).map(_ => ().asRight)),
    deleteShardedEntities.serverLogic[Future](clusterComponentsR2dbcPersistenceManagement.deleteShardedEntities(_).map(_ => ().asRight)),
  )
}
