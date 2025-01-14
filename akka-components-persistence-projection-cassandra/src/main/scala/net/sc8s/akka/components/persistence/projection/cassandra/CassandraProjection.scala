package net.sc8s.akka.components.persistence.projection.cassandra

import akka.Done
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

import scala.concurrent.Future

trait CassandraProjection extends EventSourcedT.ProjectionT {
  outerSelf: EventSourcedT#EventSourcedBaseComponentT =>

  val numberOfProjectionInstances = 1

  private[this] val eventualDone = Future.successful(Done)

  override private[components] def managedProjectionFactory(
                                                             projection: Projection[EventT, ComponentContextS with ComponentContext.Projection],
                                                             actorSystem: ActorSystem[_]
                                                           ): ManagedProjection[EventEnvelope[EventT]] = {
    val projectionIds = (0 until numberOfProjectionInstances).map(tagIndex =>
      ProjectionId(projection.name, generateTag(outerSelf.name, tagIndex))
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
            () => (envelope: EventEnvelope[EventT]) => {
              projection.handler.applyOrElse(
                envelope.event -> projectionContext(projection.name, PersistenceId.ofUniqueId(envelope.persistenceId), actorSystem),
                { _: (EventT, ComponentContextS with ComponentContext.Projection) => eventualDone }
              )
            }
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