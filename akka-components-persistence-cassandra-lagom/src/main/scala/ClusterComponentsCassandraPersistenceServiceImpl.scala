package net.sc8s.akka.components.persistence.cassandra.lagom

import akka.Done
import akka.actor.typed.ActorSystem
import akka.persistence.cassandra.cleanup.Cleanup
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.Materializer.matFromSystem
import akka.stream.scaladsl.Sink
import com.lightbend.lagom.scaladsl.api.ServiceCall
import net.sc8s.akka.circe.CirceSerializer
import net.sc8s.akka.components.ClusterComponent
import net.sc8s.akka.components.persistence.cassandra.lagom.api.ClusterComponentsCassandraPersistenceService
import net.sc8s.logstage.elastic.Logging

import scala.util.Success

trait ClusterComponentsCassandraPersistenceServiceImpl extends ClusterComponentsCassandraPersistenceService with Logging {
  val clusterComponents: Set[ClusterComponent.Component[_]]

  implicit val actorSystem: ActorSystem[_]

  import actorSystem.executionContext

  lazy val cleanup = new Cleanup(actorSystem)

  lazy val readJournal = PersistenceQuery(actorSystem).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

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
        // TODO this deletes only cassandra snapshots
        // TODO this also deletes tagged events but fails if the events have been changed
        .deleteAll(singletonPersistenceId.id, neverUsePersistenceIdAgain = false)
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

    lazy val shardedEntities =
      clusterComponents
        .map(wiredComponent => wiredComponent.component -> wiredComponent.innerComponent)
        .collect { case (_: ClusterComponent.Sharded.EventSourced, innerComponent) =>
          innerComponent.name
        }

    maybeTypeKey.fold(
      throw new Exception(s"shardedEntity with name=$name not found, existing shardedEntities=$shardedEntities")
    ) { typeKey =>
      log.infoT("deleteShardedEntities", s"$name")

      readJournal
        .currentPersistenceIds()
        .filter(_.startsWith(s"${typeKey.name}|"))
        .mapAsync(1) { id =>
          log.infoT("deleteShardedEntity", s"$id")
          // TODO this deletes only cassandra snapshots
          // TODO this also deletes tagged events but fails if the events have been changed
          cleanup.deleteAll(id, neverUsePersistenceIdAgain = false)
        }
        .runWith(Sink.fold(0)((i, _) => i + 1))
        .map { deletedEntities =>
          log.infoT("shardedEntitiesDeleted", s"$name with $deletedEntities")
          Done
        }
    }
  }
}
