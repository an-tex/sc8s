package net.sc8s.akka.components.persistence.cassandra.lagom

import akka.Done
import akka.actor.typed.ActorSystem
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.scaladsl.CurrentPersistenceIdsQuery
import akka.persistence.r2dbc.cleanup.scaladsl.EventSourcedCleanup
import akka.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import akka.stream.Materializer.matFromSystem
import akka.stream.scaladsl.Sink
import com.lightbend.lagom.scaladsl.api.ServiceCall
import net.sc8s.akka.circe.CirceSerializer
import net.sc8s.akka.components.ClusterComponent
import net.sc8s.akka.components.persistence.r2dbc.lagom.api.ClusterComponentsR2dbcPersistenceService
import net.sc8s.logstage.elastic.Logging

import scala.util.Success

trait ClusterComponentsR2dbcPersistenceServiceImpl extends ClusterComponentsR2dbcPersistenceService with Logging {
  val clusterComponents: Set[ClusterComponent.Component[_]]

  implicit val actorSystem: ActorSystem[_]

  import actorSystem.executionContext

  val queries = PersistenceQuery(actorSystem).readJournalFor[CurrentPersistenceIdsQuery](R2dbcReadJournal.Identifier)
  val cleanup = new EventSourcedCleanup(actorSystem)

  override def deleteSingletonEntity(name: String) = ServiceCall { _ =>
    val maybeSingletonPersistenceId = clusterComponents
      .map(wiredComponent => wiredComponent.component -> wiredComponent.innerComponent)
      .collectFirst { case (outerComponent: ClusterComponent.Singleton.EventSourced, innerComponent) if innerComponent.name == name =>
        innerComponent.asInstanceOf[outerComponent.BaseComponent].persistenceId
      }

    lazy val singletonEntities = clusterComponents
      .map(wiredComponent => wiredComponent.component -> wiredComponent.innerComponent)
      .collect { case (_: ClusterComponent.Singleton.EventSourced, innerComponent) =>
        innerComponent.name
      }

    maybeSingletonPersistenceId.fold(
      throw new Exception(s"singleton with name=$name not found, existing singletonEntities=$singletonEntities")
    ) { singletonPersistenceId =>
      log.infoT("deleteSingletonEntity", s"$name")
      cleanup
        .deleteAll(singletonPersistenceId.id, resetSequenceNumber = false)
        .andThen {
          case Success(_) =>
            log.infoT("singletonEntityDeleted", s"$name")
        }
    }
  }

  override def deleteShardedEntities(name: String) = ServiceCall { _ =>
    val maybeTypeKey = clusterComponents
      .map(wiredComponent => wiredComponent.component -> wiredComponent.innerComponent)
      .collectFirst { case (outerComponent: ClusterComponent.Sharded.EventSourced, innerComponent) if innerComponent.name == name =>
        innerComponent.asInstanceOf[outerComponent.BaseComponent].typeKey
      }

    lazy val shardedEntities = clusterComponents
      .map(wiredComponent => wiredComponent.component -> wiredComponent.innerComponent)
      .collect { case (_: ClusterComponent.Sharded.EventSourced, innerComponent) =>
        innerComponent.name
      }

    maybeTypeKey.fold(
      throw new Exception(s"shardedEntity with name=$name not found, existing shardedEntities=$shardedEntities")
    ) { typeKey =>
      log.infoT("deleteShardedEntities", s"$name")

      queries
        .currentPersistenceIds()
        .filter(_.startsWith(s"${typeKey.name}|"))
        .mapAsync(10) { id =>
          log.infoT("deleteShardedEntity", s"$id")
          cleanup.deleteAll(id, resetSequenceNumber = false)
        }
        .runWith(Sink.fold(0)((i, _) => i + 1))
        .map { deletedEntities =>
          log.infoT("shardedEntitiesDeleted", s"$name with $deletedEntities")
          Done
        }
    }
  }
}
