package net.sc8s.akka.components.persistence.projection.r2dbc

import akka.actor.typed.ActorSystem
import akka.persistence.query.typed.EventEnvelope
import akka.persistence.query.{Offset, PersistenceQuery, Sequence}
import akka.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import akka.persistence.typed.PersistenceId
import akka.projection.ProjectionId
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.r2dbc.scaladsl.{R2dbcProjection => AkkaR2dbcProjection, R2dbcSession}
import akka.projection.scaladsl.SourceProvider
import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import net.sc8s.akka.components.ClusterComponent.ComponentT.EventSourcedT
import net.sc8s.akka.components.ClusterComponent.{ComponentContext, Projection}
import net.sc8s.akka.components.persistence.projection.{ManagedProjection, ProjectionStatusObserver}

import scala.concurrent.{ExecutionContext, Future}

private[r2dbc] trait R2dbcProjection extends EventSourcedT.ProjectionT {
  _: EventSourcedT#EventSourcedBaseComponentT
    with EventSourcedT#BaseComponent =>

  // override this if you e.g. want to use a readonly endpoint for the projections https://discuss.lightbend.com/t/r2dbc-projections-use-read-only-hot-standby-replicas-for-projections-query/10860 . or override it in the config to customize all projections
  val readJournalPluginId = "net.sc8s.akka.components.persistence.projection.r2dbc.default.query"
}

object R2dbcProjection {
  private[r2dbc] trait FromSnapshot {
    _: EventSourcedT#EventSourcedBaseComponentT
      with EventSourcedT.SnapshotsT#SnapshotsBaseComponentT =>

    // without the type parameter you "sometimes" get an AbstractMethodError exception :( https://github.com/scala/bug/issues/11833
    def transformSnapshot[State <: StateT](state: State): EventT
  }
}

trait R2dbcShardedProjection extends R2dbcProjection {
  _: EventSourcedT#EventSourcedBaseComponentT
    with net.sc8s.akka.components.ClusterComponent.Sharded.EventSourced#BaseComponent =>

  private[this] val eventualDone = Future.successful(Done)

  override private[components] def managedProjectionFactory(
                                                             projection: Projection[EventT, ComponentContextS with ComponentContext.Projection],
                                                             actorSystem: ActorSystem[_]
                                                           ): ManagedProjection[EventEnvelope[EventT]] = {
    val numberOfProjectionInstances = actorSystem.settings.config.getInt(s"${readJournalPluginId.stripSuffix(".query")}.numberOfProjectionInstances")
    val sliceRanges = EventSourcedProvider.sliceRanges(actorSystem, readJournalPluginId, numberOfProjectionInstances)

    val projectionIds = sliceRanges.map(sliceRange =>
      ProjectionId(projection.name, s"${projection.name}-${sliceRange.min}-${sliceRange.max}")
    )

    new ManagedProjection[EventEnvelope[EventT]](
      projection.name,
      projectionIds,
      numberOfProjectionInstances,
      new ProjectionStatusObserver[EventEnvelope[EventT]]()(actorSystem) {
        override def extractSequenceNr(envelope: EventEnvelope[EventT]) = envelope.sequenceNr

        override def extractOffset(envelope: EventEnvelope[EventT]) = envelope.offset
      },
      actorSystem
    ) {

      override def projectionFactory(i: Int) = {
        val projectionId = projectionIds(i)
        val minSlice = sliceRanges(i).min
        val maxSlice = sliceRanges(i).max
        AkkaR2dbcProjection
          .atLeastOnce(
            projectionId,
            None,
            createSourceProvider(minSlice, maxSlice, actorSystem),
            () => (_: R2dbcSession, envelope: EventEnvelope[EventT]) =>
              projection.handler.applyOrElse(
                envelope.event -> projectionContext(projection.name, PersistenceId.ofUniqueId(envelope.persistenceId), actorSystem),
                { _: (EventT, ComponentContextS with ComponentContext.Projection) => eventualDone }
              )
          )
      }
    }
  }

  private[r2dbc] def createSourceProvider(minSlice: Int, maxSlice: Int, actorSystem: ActorSystem[_]): SourceProvider[Offset, EventEnvelope[EventT]] =
    EventSourcedProvider.eventsBySlices[EventT](
      actorSystem,
      readJournalPluginId,
      typeKey.name,
      minSlice,
      maxSlice,
    )
}

object R2dbcShardedProjection {
  trait FromSnapshot extends R2dbcShardedProjection with R2dbcProjection.FromSnapshot {
    _: net.sc8s.akka.components.ClusterComponent.Sharded.EventSourced#BaseComponent
      with EventSourcedT.SnapshotsT#SnapshotsBaseComponentT =>

    override private[r2dbc] def createSourceProvider(minSlice: Int, maxSlice: Int, actorSystem: ActorSystem[_]): SourceProvider[Offset, EventEnvelope[EventT]] =
      EventSourcedProvider.eventsBySlicesStartingFromSnapshots(
        actorSystem,
        readJournalPluginId,
        typeKey.name,
        minSlice,
        maxSlice,
        transformSnapshot,
      )
  }
}

// this needs to be handled separately https://discuss.lightbend.com/t/r2dbc-eventsbyslices-query-for-projections-with-cluster-singleton-without-entitytype/10089
trait R2dbcSingletonProjection extends R2dbcProjection {
  _: EventSourcedT#EventSourcedBaseComponentT
    with net.sc8s.akka.components.ClusterComponent.Singleton.EventSourced#BaseComponent =>

  private class EventsByPersistenceIdSourceProvider(
                                                     persistenceId: PersistenceId,
                                                     system: ActorSystem[_])
    extends SourceProvider[Sequence, EventEnvelope[EventT]] {
    implicit val executionContext: ExecutionContext = system.executionContext

    override def source(offset: () => Future[Option[Sequence]])
    : Future[Source[EventEnvelope[EventT], NotUsed]] =
      offset().map { offsetOpt =>
        val sequence = offsetOpt.getOrElse(Sequence(0L))
        val eventQueries = PersistenceQuery(system)
          .readJournalFor[R2dbcReadJournal](readJournalPluginId)
        createEventSource(persistenceId, sequence, eventQueries)
      }

    override def extractOffset(envelope: EventEnvelope[EventT]): Sequence = Sequence(envelope.sequenceNr)

    override def extractCreationTime(envelope: EventEnvelope[EventT]): Long =
      envelope.timestamp
  }

  private[r2dbc] def createEventSource(persistenceId: PersistenceId, sequence: Sequence, eventQueries: R2dbcReadJournal) =
    eventQueries.currentEventsByPersistenceIdTyped[EventT](persistenceId.id, sequence.value, Long.MaxValue)

  override private[components] def managedProjectionFactory(
                                                             projection: Projection[EventT, ComponentContextS with ComponentContext.Projection],
                                                             actorSystem: ActorSystem[_]
                                                           ): ManagedProjection[EventEnvelope[EventT]] = {
    val projectionIds = Seq(
      ProjectionId(projection.name, s"${projection.name}-singleton")
    )

    new ManagedProjection[EventEnvelope[EventT]](
      projection.name,
      projectionIds,
      1, // singleton projection parallelism is currently limited to 1 due to the EventsByPersistenceIdSourceProvider
      new ProjectionStatusObserver[EventEnvelope[EventT]]()(actorSystem) {
        override def extractSequenceNr(envelope: EventEnvelope[EventT]) = envelope.sequenceNr

        override def extractOffset(envelope: EventEnvelope[EventT]) = envelope.offset
      },
      actorSystem
    ) {

      override def projectionFactory(i: Int) = {
        val projectionId = projectionIds(i)
        AkkaR2dbcProjection
          .atLeastOnce(
            projectionId,
            None,
            new EventsByPersistenceIdSourceProvider(
              persistenceId,
              actorSystem,
            ),
            () => (_: R2dbcSession, envelope: EventEnvelope[EventT]) =>
              projection.handler(
                envelope.event,
                projectionContext(projection.name, PersistenceId.ofUniqueId(envelope.persistenceId), actorSystem)
              )
          )
      }
    }
  }
}

object R2dbcSingletonProjection {
  trait FromSnapshot extends R2dbcSingletonProjection with R2dbcProjection.FromSnapshot {
    _: EventSourcedT#EventSourcedBaseComponentT
      with net.sc8s.akka.components.ClusterComponent.Singleton.EventSourced#BaseComponent
      with EventSourcedT.SnapshotsT#SnapshotsBaseComponentT =>

    override private[r2dbc] def createEventSource(persistenceId: PersistenceId, sequence: Sequence, eventQueries: R2dbcReadJournal) =
      eventQueries.currentEventsByPersistenceIdStartingFromSnapshot(persistenceId.id, sequence.value, Long.MaxValue, transformSnapshot)
  }
}