package net.sc8s.lagom.akka.components

import akka.Done
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.cluster.typed.{ClusterSingleton, SingletonActor}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, RetentionCriteria}
import com.lightbend.lagom.scaladsl.server.LagomApplication
import com.softwaremill.macwire.wireSet
import net.sc8s.akka.circe.{CirceSerializer, CirceSerializerRegistry}
import net.sc8s.akka.projection.ProjectionUtils.{ManagedProjection, TagGenerator}
import net.sc8s.akka.projection.lagom.ProjectionComponents
import net.sc8s.lagom.circe.CirceAkkaSerializationComponents

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.implicitConversions
import scala.reflect.ClassTag

/*
ClusterComponents is taken by com.lightbend.lagom.scaladsl.cluster.ClusterComponents
 */
trait WiredClusterComponents extends CirceAkkaSerializationComponents with ProjectionComponents {
  _: LagomApplication =>

  private lazy val components: Set[ClusterComponent.ComponentT] = wireSet[ClusterComponent.Component].map(_.component)

  override def circeSerializerRegistry = super.circeSerializerRegistry ++ new CirceSerializerRegistry {
    override def serializers = {
      components.flatMap(_.serializers).toSeq
    }
  }

  override def projections: Set[ManagedProjection[_, _]] = super.projections ++ components.flatMap(_.managedProjections)
}

object ClusterComponent {
  private[components] trait ComponentT {
    private[components] val serializers: Seq[CirceSerializer[_]] = Nil
    private[components] val managedProjections: Seq[ManagedProjection[_, _]] = Nil

    private[components] def initProjections() = managedProjections.foreach(_.init())
  }

  private[components] trait Component {
    private[components] val component: ComponentT
  }

  trait SingletonComponent[Command] extends Component {
    val actorRef: ActorRef[Command]

    private[components] val component: SingletonT[Command]
  }

  sealed trait SingletonT[Command] extends ComponentT {
    self =>
    val name: String

    val actorSystem: ActorSystem[_]

    lazy val clusterSingleton: ClusterSingleton = ClusterSingleton(actorSystem)

    private[components] def initComponent(behavior: ActorContext[Command] => Behavior[Command]) = {
      initProjections()

      new SingletonComponent[Command] {
        override val actorRef = clusterSingleton.init(SingletonActor(
          Behaviors
            .supervise(Behaviors.setup(behavior))
            .onFailure(SupervisorStrategy.restartWithBackoff(1.second, 5.minute, 0.2)),
          name
        ))

        override private[components] val component = self
      }
    }

    def init(): SingletonComponent[Command]
  }

  case class Snapshots[State] private(retentionCriteria: RetentionCriteria, stateSerializer: CirceSerializer[State])

  case class Singleton[Command](
                                 name: String,
                                 behavior: ActorContext[Command] => Behavior[Command],
                                 commandSerializer: CirceSerializer[Command]
                               )(implicit val actorSystem: ActorSystem[_]) extends SingletonT[Command] {
    self =>

    def init() = initComponent(behavior)

    override private[components] val serializers = Seq(commandSerializer)
  }

  object Singleton {
    case class Projections[Event] private(tagGenerator: TagGenerator, projections: Seq[Projection[Event]])

    case class Projection[Event](name: String, handler: PartialFunction[Event, Future[Done]])

    def apply[Command, Event, State](
                                      name: String,
                                      eventSourcedBehavior: (ActorContext[Command], PersistenceId) => EventSourcedBehavior[Command, Event, State],
                                      commandSerializer: CirceSerializer[Command],
                                      eventSerializer: CirceSerializer[Event]
                                    )(implicit actorSystem: ActorSystem[_]) = EventSourced(
      name, eventSourcedBehavior, commandSerializer, eventSerializer
    )

    case class EventSourced[Command, Event, State] private(
                                                            name: String,
                                                            eventSourcedBehavior: (ActorContext[Command], PersistenceId) => EventSourcedBehavior[Command, Event, State],
                                                            commandSerializer: CirceSerializer[Command],
                                                            eventSerializer: CirceSerializer[Event],
                                                            projections: Option[Projections[Event]] = None,
                                                            snapshots: Option[Snapshots[State]] = None
                                                          )(implicit val actorSystem: ActorSystem[_]) extends SingletonT[Command] {
      self =>
      def withSnapshots(retentionCriteria: RetentionCriteria, stateSerializer: CirceSerializer[State]) = copy(snapshots = Some(Snapshots(retentionCriteria, stateSerializer)))

      def withProjections(projections: Projection[Event]*) = {
        val tagGenerator = TagGenerator(name, 1)
        copy(
          eventSourcedBehavior = (actorContext: ActorContext[Command], persistenceId) =>
            eventSourcedBehavior(actorContext, persistenceId)
              .withTagger(_ => Set(tagGenerator.generateTag(0)))
              .onPersistFailure(SupervisorStrategy.restartWithBackoff(1.second, 1.minute, 0.2)),
          projections = Some(Projections[Event](tagGenerator, projections))
        )
      }

      def init() = initComponent(actorContext => eventSourcedBehavior(actorContext, PersistenceId.ofUniqueId(name)))

      override private[components] val serializers = Seq(
        commandSerializer,
        eventSerializer
      ) ++ snapshots.map(_.stateSerializer)

      override private[components] val managedProjections = projections.toSeq.flatMap(projections => projections.projections.map(projection => new ManagedProjection[Event, String](projection.name, projections.tagGenerator, identity) {
        override implicit val actorSystem = self.actorSystem

        override def handle = projection.handler.compose {
          case (event, _) => event
        }
      }))
    }
  }

  trait ShardedComponent[Command, EntityId <: Sharded.EntityId] extends Component {
    private[components] val typeKey: EntityTypeKey[Command]

    private[components] val clusterSharding: ClusterSharding

    def entityRefFor(entityId: EntityId) = clusterSharding.entityRefFor(typeKey, entityId.entityId)

    private[components] val component: ShardedT[Command, EntityId]
  }

  abstract class ShardedT[Command: ClassTag, EntityId <: Sharded.EntityId] extends ComponentT {
    self =>

    val name: String

    private[components] val typeKey: EntityTypeKey[Command] = EntityTypeKey[Command](name)

    val actorSystem: ActorSystem[_]

    lazy val clusterSharding: ClusterSharding = ClusterSharding(actorSystem)

    val entityIdParser: EntityIdParser[EntityId]

    private[components] def initComponent(behavior: (ActorContext[Command], EntityId) => Behavior[Command]) = {
      initProjections()

      new ShardedComponent[Command, EntityId] {
        override val typeKey: EntityTypeKey[Command] = self.typeKey

        override val clusterSharding = self.clusterSharding

        clusterSharding.init(Entity(typeKey)(entityContext =>
          Behaviors.setup[Command](behavior(_, entityIdParser.parse(entityContext.entityId)))
        ))

        override private[components] val component = self
      }
    }

    def init(): ShardedComponent[Command, EntityId]
  }

  trait EntityIdParser[EntityId <: Sharded.EntityId] {
    def parse(entityId: String): EntityId
  }

  case class Sharded[Command: ClassTag, EntityId <: Sharded.EntityId](
                                                                       name: String,
                                                                       behavior: (ActorContext[Command], EntityId) => Behavior[Command],
                                                                       commandSerializer: CirceSerializer[Command]
                                                                     )(implicit val entityIdParser: EntityIdParser[EntityId], val actorSystem: ActorSystem[_]) extends ShardedT[Command, EntityId] {
    def init() = initComponent(behavior)

    override private[components] val serializers = Seq(commandSerializer)
  }

  object Sharded {
    trait EntityId {
      val entityId: String
    }

    case class StringEntityId(entityId: String) extends EntityId

    implicit def stringToStringEntityId(entityId: String) = StringEntityId(entityId)

    implicit val stringEntityIdParser = new EntityIdParser[StringEntityId] {
      override def parse(entityId: String) = stringToStringEntityId(entityId)
    }

    def StringEntityId[Command: ClassTag](
                                           name: String,
                                           behavior: (ActorContext[Command], StringEntityId) => Behavior[Command],
                                           commandSerializer: CirceSerializer[Command]
                                         )(implicit actorSystem: ActorSystem[_]): Sharded[Command, StringEntityId] = Sharded[Command, StringEntityId](
      name,
      behavior,
      commandSerializer
    )

    def apply[Command: ClassTag, Event, State](
                                                name: String,
                                                eventSourcedBehavior: (ActorContext[Command], StringEntityId, PersistenceId) => EventSourcedBehavior[Command, Event, State],
                                                commandSerializer: CirceSerializer[Command],
                                                eventSerializer: CirceSerializer[Event]
                                              )(implicit actorSystem: ActorSystem[_]): EventSourced[Command, Event, State, StringEntityId] = EventSourced[Command, Event, State, StringEntityId](
      name,
      eventSourcedBehavior,
      commandSerializer,
      eventSerializer
    )

    case class EventSourced[Command: ClassTag, Event, State, EntityId <: Sharded.EntityId] private(
                                                                                                    name: String,
                                                                                                    behavior: (ActorContext[Command], EntityId, PersistenceId) => EventSourcedBehavior[Command, Event, State],
                                                                                                    commandSerializer: CirceSerializer[Command],
                                                                                                    eventSerializer: CirceSerializer[Event],
                                                                                                    projections: Option[Projections[Event, EntityId]] = None,
                                                                                                    snapshots: Option[Snapshots[State]] = None
                                                                                                  )(implicit val entityIdParser: EntityIdParser[EntityId], val actorSystem: ActorSystem[_]) extends ShardedT[Command, EntityId] {
      self =>
      def withSnapshots(retentionCriteria: RetentionCriteria, stateSerializer: CirceSerializer[State]) = copy(snapshots = Some(Snapshots(retentionCriteria, stateSerializer)))

      def withProjections(projections: Projection[Event, EntityId]*) = {
        val tagGenerator = TagGenerator(name, 1)
        copy(
          behavior = (actorContext: ActorContext[Command], entityId: EntityId, persistenceId) =>
            behavior(actorContext, entityId, persistenceId)
              .withTagger(_ => Set(tagGenerator.generateTag(entityId.entityId)))
              .onPersistFailure(SupervisorStrategy.restartWithBackoff(1.second, 1.minute, 0.2)),
          projections = Some(Projections[Event, EntityId](tagGenerator, projections))
        )
      }

      def init() = initComponent((actorContext, entityId) => behavior(actorContext, entityId, PersistenceId(typeKey.name, entityId.entityId)))

      override private[components] val serializers = Seq(
        commandSerializer,
        eventSerializer
      ) ++ snapshots.map(_.stateSerializer)

      override private[components] val managedProjections = projections.toSeq.flatMap(projections => projections.projections.map(projection => new ManagedProjection[Event, EntityId](projection.name, projections.tagGenerator, entityIdParser.parse) {
        override implicit val actorSystem = self.actorSystem

        override def handle = projection.handler
      }))
    }

    case class Projections[Event, EntityId](tagGenerator: TagGenerator, projections: Seq[Projection[Event, EntityId]])

    case class Projection[Event, EntityId](name: String, handler: PartialFunction[(Event, EntityId), Future[Done]])
  }
}
