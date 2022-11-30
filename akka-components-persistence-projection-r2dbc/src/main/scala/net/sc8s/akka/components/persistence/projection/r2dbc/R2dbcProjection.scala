package net.sc8s.akka.components.persistence.projection.r2dbc

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import akka.persistence.query.typed.EventEnvelope
import akka.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import akka.persistence.typed.PersistenceId
import akka.projection.ProjectionId
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.r2dbc.scaladsl.{R2dbcProjection, R2dbcSession}
import akka.projection.scaladsl.SourceProvider
import net.sc8s.akka.components.ClusterComponent.ComponentT.EventSourcedT
import net.sc8s.akka.components.ClusterComponent.{ComponentContext, Projection}
import net.sc8s.akka.components.persistence.projection.{ManagedProjection, ProjectionStatusObserver}

trait R2dbcProjection extends EventSourcedT.ProjectionT {
  _: EventSourcedT#EventSourcedBaseComponentT
    // currently only with sharded due to https://discuss.lightbend.com/t/r2dbc-eventsbyslices-query-for-projections-with-cluster-singleton-without-entitytype/10089
    with net.sc8s.akka.components.ClusterComponent.Sharded.EventSourced#BaseComponent =>

  val numberOfProjectionInstances = 1

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
        val value: SourceProvider[Offset, EventEnvelope[EventT]] = EventSourcedProvider.eventsBySlices[EventT](
          actorSystem,
          R2dbcReadJournal.Identifier,
          typeKey.name,
          sliceRanges(i).min,
          sliceRanges(i).max,
        )
        R2dbcProjection
          .atLeastOnce(
            projectionId,
            None,
            value,
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