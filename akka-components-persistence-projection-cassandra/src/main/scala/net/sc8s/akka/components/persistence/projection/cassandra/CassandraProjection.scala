package net.sc8s.akka.components.persistence.projection.cassandra

import akka.actor.typed.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.typed.PersistenceId
import akka.projection.ProjectionId
import akka.projection.cassandra.scaladsl.CassandraProjection
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import net.sc8s.akka.components.ClusterComponent.ComponentT.EventSourcedT
import net.sc8s.akka.components.ClusterComponent.{ComponentContext, Projection}
import net.sc8s.akka.components.persistence.projection.{ManagedProjection, ProjectionStatusObserver}

trait CassandraProjection extends EventSourcedT.ProjectionT {
  _: EventSourcedT#EventSourcedBaseComponentT =>

  val numberOfProjectionInstances = 1

  override private[components] def managedProjectionFactory(
                                                             projection: Projection[EventT, ComponentContextS with ComponentContext.Projection],
                                                             actorSystem: ActorSystem[_]
                                                           ): ManagedProjection[EventEnvelope[EventT]] = {
    val projectionIds = (0 until numberOfProjectionInstances).map(tagIndex =>
      ProjectionId(projection.name, generateTag(projection.name, tagIndex))
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
        CassandraProjection
          .atLeastOnce(
            projectionId,
            EventSourcedProvider.eventsByTag(actorSystem, CassandraReadJournal.Identifier, projectionId.key),
            () => (envelope: EventEnvelope[EventT]) =>
              projection.handler(
                envelope.event,
                projectionContext(projection.name, PersistenceId.ofUniqueId(envelope.persistenceId), actorSystem)
              )
          )
      }
    }
  }

  override private[components] def tagger(name: String, context: ComponentContextS)(event: EventT) = {
    val tagIndex = math.abs(context.persistenceId.hashCode % numberOfProjectionInstances)
    Set(generateTag(name, tagIndex))
  }

  private[components] def generateTag(name: String, tagIndex: Int): String = s"$name$tagIndex"
}