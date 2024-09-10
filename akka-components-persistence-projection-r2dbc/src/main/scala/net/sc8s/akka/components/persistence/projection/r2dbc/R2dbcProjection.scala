package net.sc8s.akka.components.persistence.projection.r2dbc

import akka.actor.typed.ActorSystem
import akka.persistence.query.typed.EventEnvelope
import akka.persistence.query.{Offset, PersistenceQuery, Sequence}
import akka.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import akka.persistence.typed.PersistenceId
import akka.projection.ProjectionId
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.r2dbc.scaladsl.{R2dbcProjection, R2dbcSession}
import akka.projection.scaladsl.SourceProvider
import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import net.sc8s.akka.components.ClusterComponent.ComponentT.EventSourcedT
import net.sc8s.akka.components.ClusterComponent.{ComponentContext, Projection}
import net.sc8s.akka.components.persistence.projection.{ManagedProjection, ProjectionStatusObserver}

import scala.concurrent.{ExecutionContext, Future}

private[r2dbc] trait R2dbcProjection extends EventSourcedT.ProjectionT {
  _: EventSourcedT#EventSourcedBaseComponentT
    with net.sc8s.akka.components.ClusterComponent.ComponentT.EventSourcedT#BaseComponent =>

  val numberOfProjectionInstances = 1
}

trait R2dbcShardedProjection extends R2dbcProjection {
  _: EventSourcedT#EventSourcedBaseComponentT
    with net.sc8s.akka.components.ClusterComponent.Sharded.EventSourced#BaseComponent =>

  private[this] val eventualDone = Future.successful(Done)

  override private[components] def managedProjectionFactory(
                                                             projection: Projection[EventT, ComponentContextS with ComponentContext.Projection],
                                                             actorSystem: ActorSystem[_]
                                                           ): ManagedProjection[EventEnvelope[EventT]] = {
    val sliceRanges = EventSourcedProvider.sliceRanges(actorSystem, R2dbcReadJournal.Identifier, numberOfProjectionInstances)

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
        R2dbcProjection
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

  def createSourceProvider(minSlice: Int, maxSlice: Int, actorSystem: ActorSystem[_]): SourceProvider[Offset, EventEnvelope[EventT]] =
    EventSourcedProvider.eventsBySlices[EventT](
      actorSystem,
      R2dbcReadJournal.Identifier,
      typeKey.name,
      minSlice,
      maxSlice,
    )
}

object R2dbcShardedProjection {
  trait FromSnapshots {
    _: R2dbcShardedProjection
      with net.sc8s.akka.components.ClusterComponent.Sharded.EventSourced#BaseComponent
      with EventSourcedT.SnapshotsT#SnapshotsBaseComponentT =>

    def transformSnapshot(state: StateT): EventT

    override def createSourceProvider(minSlice: Int, maxSlice: Int, actorSystem: ActorSystem[_]): SourceProvider[Offset, EventEnvelope[EventT]] =
      EventSourcedProvider.eventsBySlicesStartingFromSnapshots(
        actorSystem,
        R2dbcReadJournal.Identifier,
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

  import akka.persistence.query.EventEnvelope

  private class EventsByPersistenceIdSourceProvider(
                                                     persistenceId: PersistenceId,
                                                     system: ActorSystem[_])
    extends SourceProvider[Sequence, EventEnvelope] {
    implicit val executionContext: ExecutionContext = system.executionContext

    override def source(offset: () => Future[Option[Sequence]])
    : Future[Source[EventEnvelope, NotUsed]] =
      offset().map { offsetOpt =>
        val sequence = offsetOpt.getOrElse(Sequence(0L))
        val eventQueries = PersistenceQuery(system)
          .readJournalFor[R2dbcReadJournal](R2dbcReadJournal.Identifier)
        eventQueries.currentEventsByPersistenceId(persistenceId.id, sequence.value, Long.MaxValue)
      }

    override def extractOffset(envelope: EventEnvelope): Sequence = Sequence(envelope.sequenceNr)

    override def extractCreationTime(envelope: EventEnvelope): Long =
      envelope.timestamp
  }

  override private[components] def managedProjectionFactory(
                                                             projection: Projection[EventT, ComponentContextS with ComponentContext.Projection],
                                                             actorSystem: ActorSystem[_]
                                                           ): ManagedProjection[EventEnvelope] = {
    val projectionIds = Seq(
      ProjectionId(projection.name, s"${projection.name}-singleton")
    )

    new ManagedProjection[EventEnvelope](
      projection.name,
      projectionIds,
      numberOfProjectionInstances,
      new ProjectionStatusObserver[EventEnvelope]()(actorSystem) {
        override def extractSequenceNr(envelope: EventEnvelope) = envelope.sequenceNr

        override def extractOffset(envelope: EventEnvelope) = envelope.offset
      },
      actorSystem
    ) {

      override def projectionFactory(i: Int) = {
        val projectionId = projectionIds(i)
        R2dbcProjection
          .atLeastOnce(
            projectionId,
            None,
            new EventsByPersistenceIdSourceProvider(
              persistenceId,
              actorSystem,
            ),
            () => (_: R2dbcSession, envelope: EventEnvelope) =>
              projection.handler(
                envelope.event.asInstanceOf[EventT],
                projectionContext(projection.name, PersistenceId.ofUniqueId(envelope.persistenceId), actorSystem)
              )
          )
      }
    }
  }
}