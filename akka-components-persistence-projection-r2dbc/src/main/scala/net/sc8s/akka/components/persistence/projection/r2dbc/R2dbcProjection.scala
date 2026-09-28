package net.sc8s.akka.components.persistence.projection.r2dbc

import akka.Done
import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import akka.persistence.query.typed.EventEnvelope
import akka.persistence.typed.PersistenceId
import akka.projection.ProjectionId
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.r2dbc.scaladsl.{R2dbcSession, R2dbcProjection => AkkaR2dbcProjection}
import akka.projection.scaladsl.SourceProvider
import net.sc8s.akka.components.ClusterComponent.ComponentT.EventSourcedT
import net.sc8s.akka.components.ClusterComponent.{ComponentContext, Projection}
import net.sc8s.akka.components.persistence.projection.{ManagedProjection, ProjectionStatusObserver}

import scala.concurrent.Future

private[r2dbc] trait R2dbcProjection extends EventSourcedT.ProjectionT {
  _: EventSourcedT#EventSourcedBaseComponentT
    with EventSourcedT#BaseComponent =>

  // override this if you e.g. want to use a readonly endpoint for the projections https://discuss.lightbend.com/t/r2dbc-projections-use-read-only-hot-standby-replicas-for-projections-query/10860 . or override it in the config to customize all projections
  val readJournalPluginId = "net.sc8s.akka.components.persistence.projection.r2dbc.default.query"

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
        override def extractSequenceNr(envelope: EventEnvelope[EventT]): Long = envelope.sequenceNr

        override def extractOffset(envelope: EventEnvelope[EventT]): Offset = envelope.offset
      },
      actorSystem
    ) {

      override def projectionFactory(i: Int): akka.projection.Projection[EventEnvelope[EventT]] = {
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

  private[r2dbc] val entityType: String

  private[r2dbc] def createSourceProvider(minSlice: Int, maxSlice: Int, actorSystem: ActorSystem[_]): SourceProvider[Offset, EventEnvelope[EventT]] =
    EventSourcedProvider.eventsBySlices[EventT](
      actorSystem,
      readJournalPluginId,
      entityType,
      minSlice,
      maxSlice,
    )
}

object R2dbcProjection {
  private[r2dbc] trait FromSnapshot {
    _: R2dbcProjection
      with EventSourcedT#EventSourcedBaseComponentT
      with EventSourcedT.SnapshotsT#SnapshotsBaseComponentT =>

    // without the type parameter you "sometimes" get an AbstractMethodError exception :( https://github.com/scala/bug/issues/11833
    def transformSnapshot[State <: StateT](state: State): EventT

    override private[r2dbc] def createSourceProvider(minSlice: Int, maxSlice: Int, actorSystem: ActorSystem[_]): SourceProvider[Offset, EventEnvelope[EventT]] =
      EventSourcedProvider.eventsBySlicesStartingFromSnapshots(
        actorSystem,
        readJournalPluginId,
        name,
        minSlice,
        maxSlice,
        transformSnapshot,
      )

  }
}

trait R2dbcShardedProjection extends R2dbcProjection {
  _: EventSourcedT#EventSourcedBaseComponentT
    with net.sc8s.akka.components.ClusterComponent.Sharded.EventSourced#BaseComponent =>

  override private[r2dbc] lazy val entityType: String = typeKey.name
}

object R2dbcShardedProjection {
  trait FromSnapshot extends R2dbcShardedProjection with R2dbcProjection.FromSnapshot {
    _: net.sc8s.akka.components.ClusterComponent.Sharded.EventSourced#BaseComponent
      with EventSourcedT.SnapshotsT#SnapshotsBaseComponentT =>
  }
}

trait R2dbcSingletonProjection extends R2dbcProjection {
  _: EventSourcedT#EventSourcedBaseComponentT
    with net.sc8s.akka.components.ClusterComponent.Singleton.EventSourced#BaseComponent =>

  override private[r2dbc] lazy val entityType: String = name

  override private[components] def managedProjections(implicit actorSystem: ActorSystem[_]) = {
    // don't make this check directly in the class body as you wouldn't see the overridden value
    if (legacyPersistenceIdHandling) throw new IllegalArgumentException("Legacy persistenceId handling is not supported for R2dbcSingletonProjection")
    super.managedProjections
  }
}

object R2dbcSingletonProjection {
  trait FromSnapshot extends R2dbcSingletonProjection with R2dbcProjection.FromSnapshot {
    _: EventSourcedT#EventSourcedBaseComponentT
      with net.sc8s.akka.components.ClusterComponent.Singleton.EventSourced#BaseComponent
      with EventSourcedT.SnapshotsT#SnapshotsBaseComponentT =>
  }
}