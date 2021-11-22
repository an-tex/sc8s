package net.sc8s.lagom.akka.components

import akka.Done
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import akka.cluster.typed.{ClusterSingleton, SingletonActor}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, RetentionCriteria}
import com.lightbend.lagom.scaladsl.server.LagomApplication
import com.softwaremill.macwire.wireSet
import io.scalaland.chimney.dsl._
import izumi.logstage.api.Log.CustomContext
import net.sc8s.akka.circe.{CirceSerializer, CirceSerializerRegistry}
import net.sc8s.akka.projection.ProjectionUtils.{ManagedProjection, TagGenerator}
import net.sc8s.akka.projection.lagom.ProjectionComponents
import net.sc8s.lagom.circe.CirceAkkaSerializationComponents
import net.sc8s.logstage.elastic.Logging

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.implicitConversions
import scala.reflect.ClassTag
import scala.util.chaining.scalaUtilChainingOps

/*
ClusterComponents is taken by com.lightbend.lagom.scaladsl.cluster.ClusterComponents
 */
trait WiredClusterComponents extends CirceAkkaSerializationComponents with ProjectionComponents {
  _: LagomApplication =>

  private lazy val components: Set[ClusterComponent.ComponentT[_, _, _]] = wireSet[ClusterComponent.Component[_]].map(_.component)

  override def circeSerializerRegistry = super.circeSerializerRegistry ++ new CirceSerializerRegistry {
    override def serializers = {
      components.flatMap(_.serializers).toSeq
    }
  }

  override def projections: Set[ManagedProjection[_, _]] = super.projections ++ components.flatMap(_.managedProjections)
}

object ClusterComponent {
  abstract class ComponentContext[Command: ClassTag] extends Logging {
    val actorContext: ActorContext[Command]

    override protected def logContext = super.logContext + CustomContext(
      "actorPath" -> actorContext.self.path.toStringWithoutAddress,
      "actorName" -> actorContext.self.path.name
    )

    override lazy val loggerClass = implicitly[ClassTag[Command]].runtimeClass.getName.takeWhile(_ != '$')
  }

  object ComponentContext {
    trait EventSourced[Command] extends ComponentContext[Command] {
      val persistenceId: PersistenceId

      override protected def logContext = super.logContext + CustomContext(
        "persistenceId" -> persistenceId.id
      )
    }

    trait Sharded[Command, SerializableCommand <: Command, EntityId <: ClusterComponent.Sharded.EntityId] extends ComponentContext[Command] {
      val entityId: EntityId

      def entityRef(entityId: EntityId): EntityRef[SerializableCommand]

      override protected def logContext = super.logContext + entityId.logContext
    }
  }

  private[components] trait ComponentT[
    Command,
    SerializableCommand <: Command,
    ComponentContextT <: ComponentContext[Command],
  ] {

    val name: String

    type BehaviorS <: Behavior[Command]

    type ComponentContextS <: ComponentContextT

    def behavior: ComponentContextS => BehaviorS

    def init(): Component[SerializableCommand]

    val commandSerializer: CirceSerializer[SerializableCommand]

    val actorSystem: ActorSystem[_]

    val logContext: CustomContext

    private[components] def serializers: Seq[CirceSerializer[_]] = Seq(commandSerializer)

    private[components] val managedProjections: Seq[ManagedProjection[_, _]] = Nil

    private[components] def initProjections() = managedProjections.foreach(_.init())
  }

  private[components] object ComponentT {
    sealed trait EventSourced[
      Command,
      SerializableCommand <: Command,
      Event,
      State,
      ComponentContextT <: ComponentContext[Command] with ComponentContext.EventSourced[Command],
      ProjectionT,
    ] extends ComponentT[Command, SerializableCommand, ComponentContextT] {
      val eventSerializer: CirceSerializer[Event]

      def withSnapshots(retentionCriteria: RetentionCriteria, stateSerializer: CirceSerializer[State]): EventSourced[Command, SerializableCommand, Event, State, ComponentContextT, ProjectionT] with EventSourced.Snapshots[Command, SerializableCommand, Event, State, ComponentContextT, ProjectionT]

      override private[components] def serializers = super.serializers :+ eventSerializer

      override type BehaviorS = EventSourcedBehavior[Command, Event, State]

      type EventSourcedS = EventSourced[Command, SerializableCommand, Event, State, ComponentContextS, ProjectionT]

      private[components] def addProjections(
                                              behavior: ComponentContextS => EventSourcedBehavior[Command, Event, State],
                                              projections: Seq[ProjectionT]
                                            ): EventSourcedS

      val tagGenerator: TagGenerator

      def generateTag(context: ComponentContextS): String

      val projections: Seq[ProjectionT]

      def withProjections(projections: ProjectionT*): EventSourcedS =
        addProjections(
          (context: ComponentContextS) =>
            behavior(context)
              .withTagger(_ => Set(generateTag(context)))
              .onPersistFailure(SupervisorStrategy.restartWithBackoff(1.second, 1.minute, 0.2)),
          projections
        )
    }

    object EventSourced {
      sealed trait Snapshots[Command, SerializableCommand <: Command, Event, State, ComponentContextT <: ComponentContext[Command] with ComponentContext.EventSourced[Command], ProjectionT] extends EventSourced[Command, SerializableCommand, Event, State, ComponentContextT, ProjectionT] {
        val retentionCriteria: RetentionCriteria

        val stateSerializer: CirceSerializer[State]

        override private[components] def serializers = super.serializers :+ stateSerializer
      }
    }
  }

  private[components] sealed trait Component[SerializableCommand] {
    private[components] val component: ComponentT[_, _, _]
  }

  trait SingletonComponent[SerializableCommand] extends Component[SerializableCommand] {
    val actorRef: ActorRef[SerializableCommand]
  }

  private[components] sealed trait SingletonT[
    Command,
    SerializableCommandT <: Command,
    ComponentContextT <: ComponentContext[Command]
  ] extends ComponentT[Command, SerializableCommandT, ComponentContextT] {
    self =>
    lazy val clusterSingleton: ClusterSingleton = ClusterSingleton(actorSystem)

    override type ComponentContextS = ComponentContextT

    def fromActorContext(actorContext: ActorContext[Command]): ComponentContextS

    def init(): SingletonComponent[SerializableCommandT] = {
      initProjections()

      new SingletonComponent[SerializableCommandT] {

        override val actorRef = clusterSingleton.init(SingletonActor(
          Behaviors
            .supervise(Behaviors.setup[Command](fromActorContext(_).pipe(behavior)).narrow[SerializableCommandT])
            .onFailure(SupervisorStrategy.restartWithBackoff(1.second, 5.minute, 0.2)),
          name
        ))

        override private[components] val component = self
      }
    }
  }

  case class Singleton[Command: ClassTag, SerializableCommand <: Command](
                                                                           name: String,
                                                                           behavior: ComponentContext[Command] => Behavior[Command],
                                                                           commandSerializer: CirceSerializer[SerializableCommand],
                                                                           logContext: CustomContext = CustomContext()
                                                                         )(implicit val actorSystem: ActorSystem[_]) extends SingletonT[Command, SerializableCommand, ComponentContext[Command]] {
    self =>

    override def fromActorContext(_actorContext: ActorContext[Command]) = new ComponentContext[Command] {
      override val actorContext = _actorContext

      override protected def logContext = super.logContext + self.logContext
    }

    override type BehaviorS = Behavior[Command]
  }

  object Singleton {

    case class Projection[Event](name: String, handler: PartialFunction[Event, Future[Done]])

    private[components] abstract class EventSourcedT[Command: ClassTag, SerializableCommand <: Command, Event, State] extends SingletonT[Command, SerializableCommand, ComponentContext[Command] with ComponentContext.EventSourced[Command]] with ComponentT.EventSourced[Command, SerializableCommand, Event, State, ComponentContext[Command] with ComponentContext.EventSourced[Command], Projection[Event]] {
      self =>
      implicit val actorSystem: ActorSystem[_]

      override def fromActorContext(_actorContext: ActorContext[Command]) = new ComponentContext[Command] with ComponentContext.EventSourced[Command] {
        override val persistenceId = PersistenceId.ofUniqueId(name)
        override val actorContext = _actorContext

        override protected def logContext = super.logContext + self.logContext
      }

      override type ComponentContextS = ComponentContext[Command] with ComponentContext.EventSourced[Command]

      lazy val tagGenerator = TagGenerator(name, 1)

      override private[components] val managedProjections = projections.map(projection => new ManagedProjection[Event, String](projection.name, tagGenerator, identity) {
        override implicit val actorSystem = self.actorSystem

        override def handle = projection.handler.compose {
          case (event, _) => event
        }
      })

      override def generateTag(context: EventSourcedT.this.ComponentContextS) = tagGenerator.generateTag(0)
    }

    case class EventSourced[Command: ClassTag, SerializableCommand <: Command, Event, State](
                                                                                              name: String,
                                                                                              behavior: ComponentContext[Command] with ComponentContext.EventSourced[Command] => EventSourcedBehavior[Command, Event, State],
                                                                                              commandSerializer: CirceSerializer[SerializableCommand],
                                                                                              eventSerializer: CirceSerializer[Event],
                                                                                              projections: Seq[Projection[Event]] = Nil,
                                                                                              logContext: CustomContext = CustomContext()
                                                                                            )(implicit val actorSystem: ActorSystem[_]) extends EventSourcedT[Command, SerializableCommand, Event, State] {
      self =>
      def withSnapshots(retentionCriteria: RetentionCriteria, stateSerializer: CirceSerializer[State]) =
        this.into[EventSourcedWithSnapshots[Command, SerializableCommand, Event, State]]
          .withFieldConst(_.retentionCriteria, retentionCriteria)
          .withFieldConst(_.stateSerializer, stateSerializer)
          .transform

      override private[components] def addProjections(
                                                       behavior: ComponentContextS => BehaviorS,
                                                       projections: Seq[Projection[Event]]
                                                     ) = copy(
        behavior = behavior,
        projections = projections
      )
    }

    case class EventSourcedWithSnapshots[Command: ClassTag, SerializableCommand <: Command, Event, State] private[components](
                                                                                                                               name: String,
                                                                                                                               behavior: ComponentContext[Command] with ComponentContext.EventSourced[Command] => EventSourcedBehavior[Command, Event, State],
                                                                                                                               commandSerializer: CirceSerializer[SerializableCommand],
                                                                                                                               eventSerializer: CirceSerializer[Event],
                                                                                                                               retentionCriteria: RetentionCriteria,
                                                                                                                               stateSerializer: CirceSerializer[State],
                                                                                                                               projections: Seq[Projection[Event]] = Nil,
                                                                                                                               logContext: CustomContext = CustomContext()
                                                                                                                             )(implicit val actorSystem: ActorSystem[_]) extends EventSourcedT[Command, SerializableCommand, Event, State] with ComponentT.EventSourced.Snapshots[Command, SerializableCommand, Event, State, ComponentContext[Command] with ComponentContext.EventSourced[Command], Projection[Event]] {

      override def withSnapshots(retentionCriteria: RetentionCriteria, stateSerializer: CirceSerializer[State]) = copy(retentionCriteria = retentionCriteria, stateSerializer = stateSerializer)

      override private[components] def addProjections(
                                                       behavior: ComponentContextS => BehaviorS,
                                                       projections: Seq[Projection[Event]]
                                                     ) = copy(
        behavior = behavior,
        projections = projections
      )
    }
  }

  private[components] type ShardedComponentContext[Command, SerializableCommand <: Command, EntityId <: Sharded.EntityId] = ComponentContext[Command] with ComponentContext.Sharded[Command, SerializableCommand, EntityId]

  trait ShardedComponent[SerializableCommand, EntityId <: Sharded.EntityId] extends Component[SerializableCommand] {
    def entityRef(entityId: EntityId): EntityRef[SerializableCommand]
  }

  private[components] abstract class ShardedT[Command: ClassTag, SerializableCommand <: Command, EntityId <: Sharded.EntityId, ComponentContextT <: ShardedComponentContext[Command, SerializableCommand, EntityId]] extends ComponentT[Command, SerializableCommand, ComponentContextT] {
    self =>

    override type ComponentContextS = ComponentContextT

    private[components] val typeKey: EntityTypeKey[SerializableCommand] = EntityTypeKey[Command](name)

    lazy val clusterSharding: ClusterSharding = ClusterSharding(actorSystem)

    val entityIdParser: EntityIdParser[EntityId]

    def fromActorContext(actorContext: ActorContext[Command], entityId: EntityId): ComponentContextS

    def init(): ShardedComponent[SerializableCommand, EntityId] = {
      initProjections()

      new ShardedComponent[SerializableCommand, EntityId] {
        clusterSharding.init(Entity(typeKey)(entityContext =>
          Behaviors.setup[Command](_actorContext => behavior(fromActorContext(_actorContext, entityIdParser.parse(entityContext.entityId)))).narrow[SerializableCommand]
        ))
        //override private[components] val serializers = self.serializers

        override private[components] val component = self

        override def entityRef(entityId: EntityId) = clusterSharding.entityRefFor(typeKey, entityId.entityId)
      }
    }
  }

  trait EntityIdParser[EntityId <: Sharded.EntityId] {
    def parse(entityId: String): EntityId
  }

  case class Sharded[Command: ClassTag, SerializableCommand <: Command, EntityId <: Sharded.EntityId](
                                                                                                       name: String,
                                                                                                       behavior: ShardedComponentContext[Command, SerializableCommand, EntityId] => Behavior[Command],
                                                                                                       commandSerializer: CirceSerializer[SerializableCommand],
                                                                                                       logContext: CustomContext = CustomContext()
                                                                                                     )(implicit val entityIdParser: EntityIdParser[EntityId], val actorSystem: ActorSystem[_]) extends ShardedT[Command, SerializableCommand, EntityId, ShardedComponentContext[Command, SerializableCommand, EntityId]] {
    self =>

    override def fromActorContext(_actorContext: ActorContext[Command], _entityId: EntityId) = new ComponentContext[Command] with ComponentContext.Sharded[Command, SerializableCommand, EntityId] {
      override val entityId = _entityId
      override val actorContext = _actorContext

      override def entityRef(entityId: EntityId) = clusterSharding.entityRefFor(typeKey, entityId.entityId)

      override protected def logContext = super.logContext + self.logContext
    }

    override type BehaviorS = Behavior[Command]
  }

  object Sharded {
    trait EntityId {
      val entityId: String

      val logContext = CustomContext(
        "entityId" -> entityId
      )
    }

    case class StringEntityId(entityId: String) extends EntityId

    implicit def stringToStringEntityId(entityId: String) = StringEntityId(entityId)

    implicit val stringEntityIdParser = new EntityIdParser[StringEntityId] {
      override def parse(entityId: String) = stringToStringEntityId(entityId)
    }

    def StringEntityId[Command: ClassTag, SerializableCommand <: Command](
                                                                           name: String,
                                                                           behavior: ShardedComponentContext[Command, SerializableCommand, StringEntityId] => Behavior[Command],
                                                                           commandSerializer: CirceSerializer[SerializableCommand]
                                                                         )(implicit actorSystem: ActorSystem[_]): Sharded[Command, SerializableCommand, StringEntityId] = Sharded[Command, SerializableCommand, StringEntityId](
      name,
      behavior,
      commandSerializer
    )

    private[components] type ShardedEventSourcedComponentContext[Command, SerializableCommand <: Command, EntityId <: Sharded.EntityId] = ComponentContext[Command] with ComponentContext.Sharded[Command, SerializableCommand, EntityId] with ComponentContext.EventSourced[Command]

    private[components] abstract class EventSourcedT[Command: ClassTag, SerializableCommand <: Command, Event, State, EntityId <: Sharded.EntityId] extends ShardedT[Command, SerializableCommand, EntityId, ShardedEventSourcedComponentContext[Command, SerializableCommand, EntityId]] with ComponentT.EventSourced[Command, SerializableCommand, Event, State, ShardedEventSourcedComponentContext[Command, SerializableCommand, EntityId], Projection[Event, EntityId]] {
      self =>
      implicit val actorSystem: ActorSystem[_]

      override type ComponentContextS = ShardedEventSourcedComponentContext[Command, SerializableCommand, EntityId]

      override def fromActorContext(_actorContext: ActorContext[Command], _entityId: EntityId) = new ComponentContext[Command] with ComponentContext.Sharded[Command, SerializableCommand, EntityId] with ComponentContext.EventSourced[Command] {
        override val entityId = _entityId
        override val persistenceId = PersistenceId(typeKey.name, entityId.entityId)
        override val actorContext = _actorContext

        override def entityRef(entityId: EntityId) = clusterSharding.entityRefFor(typeKey, entityId.entityId)

        override protected def logContext = super.logContext + self.logContext
      }

      lazy val tagGenerator = TagGenerator(name, 1)

      override private[components] val managedProjections = projections.map(projection => new ManagedProjection[Event, EntityId](projection.name, tagGenerator, entityIdParser.parse) {
        override implicit val actorSystem = self.actorSystem

        override def handle = projection.handler
      })

      override def generateTag(context: ComponentContextS) = tagGenerator.generateTag(context.entityId.entityId)
    }

    def EventSourcedStringEntityId[Command: ClassTag, SerializableCommand <: Command, Event, State](
                                                                                                     name: String,
                                                                                                     eventSourcedBehavior: ShardedEventSourcedComponentContext[Command, SerializableCommand, StringEntityId] => EventSourcedBehavior[Command, Event, State],
                                                                                                     commandSerializer: CirceSerializer[SerializableCommand],
                                                                                                     eventSerializer: CirceSerializer[Event],
                                                                                                     logContext: CustomContext = CustomContext()
                                                                                                   )(implicit actorSystem: ActorSystem[_]): EventSourced[Command, SerializableCommand, Event, State, StringEntityId] = EventSourced[Command, SerializableCommand, Event, State, StringEntityId](
      name,
      eventSourcedBehavior,
      commandSerializer,
      eventSerializer,
      logContext = logContext
    )

    case class EventSourced[Command: ClassTag, SerializableCommand <: Command, Event, State, EntityId <: Sharded.EntityId] private(
                                                                                                                                    name: String,
                                                                                                                                    behavior: ShardedEventSourcedComponentContext[Command, SerializableCommand, EntityId] => EventSourcedBehavior[Command, Event, State],
                                                                                                                                    commandSerializer: CirceSerializer[SerializableCommand],
                                                                                                                                    eventSerializer: CirceSerializer[Event],
                                                                                                                                    projections: Seq[Projection[Event, EntityId]] = Nil,
                                                                                                                                    logContext: CustomContext = CustomContext()
                                                                                                                                  )(implicit val entityIdParser: EntityIdParser[EntityId], val actorSystem: ActorSystem[_]) extends EventSourcedT[Command, SerializableCommand, Event, State, EntityId] {
      self =>
      def withSnapshots(retentionCriteria: RetentionCriteria, stateSerializer: CirceSerializer[State]) =
        this.into[EventSourcedWithSnapshots[Command, SerializableCommand, Event, State, EntityId]]
          .withFieldConst(_.retentionCriteria, retentionCriteria)
          .withFieldConst(_.stateSerializer, stateSerializer)
          .transform

      override private[components] def addProjections(
                                                       behavior: ComponentContextS => BehaviorS,
                                                       projections: Seq[Projection[Event, EntityId]]
                                                     ) = copy(
        behavior = behavior,
        projections = projections
      )
    }

    case class EventSourcedWithSnapshots[Command: ClassTag, SerializableCommand <: Command, Event, State, EntityId <: Sharded.EntityId] private[components](
                                                                                                                                                             name: String,
                                                                                                                                                             behavior: ShardedEventSourcedComponentContext[Command, SerializableCommand, EntityId] => EventSourcedBehavior[Command, Event, State],
                                                                                                                                                             commandSerializer: CirceSerializer[SerializableCommand],
                                                                                                                                                             eventSerializer: CirceSerializer[Event],
                                                                                                                                                             retentionCriteria: RetentionCriteria,
                                                                                                                                                             stateSerializer: CirceSerializer[State],
                                                                                                                                                             projections: Seq[Projection[Event, EntityId]] = Nil,
                                                                                                                                                             logContext: CustomContext = CustomContext()

                                                                                                                                                           )(implicit val entityIdParser: EntityIdParser[EntityId], val actorSystem: ActorSystem[_]) extends EventSourcedT[Command, SerializableCommand, Event, State, EntityId] with ComponentT.EventSourced.Snapshots[Command, SerializableCommand, Event, State, ShardedEventSourcedComponentContext[Command, SerializableCommand, EntityId], Projection[Event, EntityId]] {

      override def withSnapshots(retentionCriteria: RetentionCriteria, stateSerializer: CirceSerializer[State]) = copy(
        retentionCriteria = retentionCriteria,
        stateSerializer = stateSerializer
      )

      override private[components] def addProjections(
                                                       behavior: ComponentContextS => BehaviorS,
                                                       projections: Seq[Projection[Event, EntityId]]
                                                     ) = copy(
        behavior = behavior,
        projections = projections
      )
    }

    case class Projection[Event, EntityId](name: String, handler: PartialFunction[(Event, EntityId), Future[Done]])
  }
}
