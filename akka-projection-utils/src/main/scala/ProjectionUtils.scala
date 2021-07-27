package net.sc8s.akka.projection

import akka.actor.typed.{ActorSystem, Behavior}
import akka.cluster.sharding.typed.scaladsl.{EntityContext, ShardedDaemonProcess}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.projection.cassandra.scaladsl.CassandraProjection
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.scaladsl.{Handler, ProjectionManagement}
import akka.projection.{ProjectionBehavior, ProjectionContext, ProjectionId}
import akka.stream.scaladsl.FlowWithContext
import akka.{Done, NotUsed}
import cats.implicits.{catsStdInstancesForFuture, toTraverseOps}
import cats.instances.list._
import net.sc8s.logstage.elastic.Logging

import scala.concurrent.{ExecutionContext, Future}

object ProjectionUtils {
  case class TagGenerator(tagPrefix: String, eventProcessorParallelism: Int = 4) {
    def generateTag(entityContext: EntityContext[_]): String = {
      val tagIndex = math.abs(entityContext.entityId.hashCode % eventProcessorParallelism)
      generateTag(tagIndex)
    }

    def generateTag(tagIndex: Int): String = s"$tagPrefix$tagIndex"
  }

  trait ManagedProjection[Event, EntityIdT] {
    val projectionName: String
    val tagGenerator: TagGenerator
    val entityIdExtractor: String => EntityIdT

    val projectionIds = (0 until tagGenerator.eventProcessorParallelism).map(tagIndex =>
      ProjectionId(projectionName, tagGenerator.generateTag(tagIndex))
    )

    def handle: PartialFunction[(Event, EntityIdT), Future[Done]]

    def handler = new Handler[EventEnvelope[Event]] {
      override def process(envelope: EventEnvelope[Event]) = {
        val entityId = entityIdExtractor(envelope.persistenceId)
        handle.lift(envelope.event, entityId).getOrElse(Future.successful(Done))
      }
    }

    def init(
              shardedDaemonProcess: ShardedDaemonProcess,
              shardedDaemonProcessName: String = s"$projectionName-projection"
            )(implicit actorSystem: ActorSystem[_]) = shardedDaemonProcess.init[ProjectionBehavior.Command](
      shardedDaemonProcessName,
      tagGenerator.eventProcessorParallelism,
      tagIndex => {
        val projectionId = projectionIds(tagIndex)
        ProjectionBehavior(CassandraProjection
          .atLeastOnce(
            projectionId,
            EventSourcedProvider.eventsByTag[Event](actorSystem, CassandraReadJournal.Identifier, projectionId.key),
            () => handler
          )
          .withStatusObserver(ProjectionsStatusObserver.statusObserver[Event](projectionId))
        )
      },
      ProjectionBehavior.Stop
    )
  }

  case class TaggedProjection(
                               projectionName: String,
                               tagGenerator: TagGenerator
                             ) extends Logging {
    val projectionIds = (0 until tagGenerator.eventProcessorParallelism).map(tagIndex =>
      ProjectionId(projectionName, tagGenerator.generateTag(tagIndex))
    )

    def rebuildProjections()(
      implicit actorSystem: ActorSystem[_],
      executionContext: ExecutionContext
    ): Future[Done] = {
      log.info(s"${"rebuildingProjection" -> "tag"} ${projectionIds.map(_.id) -> "projectionIds"}")
      projectionIds
        .map(ProjectionManagement(actorSystem).clearOffset)
        .toList
        .sequence
        .map(_ => Done)
    }

    def initProjection(
                        shardedDaemonProcess: ShardedDaemonProcess,
                        shardedDaemonProcessName: String = s"$projectionName-projection"
                      )(
                        behavior: ProjectionId => Behavior[ProjectionBehavior.Command]
                      ) =
      shardedDaemonProcess.init[ProjectionBehavior.Command](
        shardedDaemonProcessName,
        tagGenerator.eventProcessorParallelism,
        tagIndex => {
          val projectionId = projectionIds(tagIndex)
          behavior(projectionId)
        },
        ProjectionBehavior.Stop
      )

    def initCassandraProjection[Event](
                                        shardedDaemonProcess: ShardedDaemonProcess,
                                        shardedDaemonProcessName: String = s"$projectionName-projection"
                                      )(
                                        flow:
                                        FlowWithContext[
                                          EventEnvelope[Event],
                                          ProjectionContext,
                                          EventEnvelope[Event],
                                          ProjectionContext,
                                          NotUsed
                                        ] =>
                                          FlowWithContext[
                                            EventEnvelope[Event],
                                            ProjectionContext,
                                            Done,
                                            ProjectionContext,
                                            _
                                          ]
                                      )(
                                        implicit system: ActorSystem[_]
                                      ) = initProjection(shardedDaemonProcess, shardedDaemonProcessName)(projectionId =>
      ProjectionBehavior(CassandraProjection
        .atLeastOnceFlow(
          projectionId,
          EventSourcedProvider.eventsByTag[Event](system, CassandraReadJournal.Identifier, projectionId.key),
          flow(FlowWithContext[EventEnvelope[Event], ProjectionContext])
        )
        .withStatusObserver(ProjectionsStatusObserver.statusObserver[Event](projectionId))
      )
    )

    def initDefaultProjectionWithEntityIdExtractor[Event, EntityIdT](
                                                                      shardedDaemonProcess: ShardedDaemonProcess,
                                                                      shardedDaemonProcessName: String = s"$projectionName-projection",
                                                                    )(
                                                                      entityIdExtractor: String => EntityIdT
                                                                    )(
                                                                      pf: PartialFunction[(Event, EntityIdT), Future[Done]]
                                                                    )(
                                                                      implicit system: ActorSystem[_]
                                                                    ) = initCassandraProjection[Event](shardedDaemonProcess, shardedDaemonProcessName)(
      _
        .map { envelope =>
          envelope.event -> entityIdExtractor(envelope.persistenceId)
        }
        .mapAsync(1)(pf.lift(_).getOrElse(Future.successful(Done)))
    )

    type EntityId = String

    def initDefaultProjection[Event](
                                      shardedDaemonProcess: ShardedDaemonProcess,
                                      shardedDaemonProcessName: String = s"$projectionName-projection"
                                    )(
                                      pf: PartialFunction[(Event, EntityId), Future[Done]]
                                    )(
                                      implicit system: ActorSystem[_]
                                    ) =
      initDefaultProjectionWithEntityIdExtractor[Event, EntityId](
        shardedDaemonProcess,
        shardedDaemonProcessName
      ) {
        case s"$_|$entityId" => entityId
      }(pf)
  }
}