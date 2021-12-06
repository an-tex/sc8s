package net.sc8s.akka.components

import ClusterComponent.Sharded.{EntityIdCodec, InnerShardedComponent}
import ClusterComponent.Singleton.InnerSingletonComponent

import akka.Done
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import akka.cluster.typed.{ClusterSingleton, ClusterSingletonSettings, SingletonActor}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, RetentionCriteria}
import izumi.logstage.api.Log.CustomContext
import net.sc8s.akka.circe.CirceSerializer
import net.sc8s.akka.persistence.utils.SignalHandlers
import net.sc8s.akka.projection.ProjectionUtils.{ManagedProjection, TagGenerator}
import net.sc8s.logstage.elastic.Logging

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag
import scala.util.chaining.scalaUtilChainingOps

object ClusterComponent {
  private[components] sealed trait ComponentT {
    type Command

    type SerializableCommand <: Command

    val name: String

    val additionalSerializers: Seq[CirceSerializer[_]] = Nil

    val commandSerializer: CirceSerializer[SerializableCommand]

    def serializers = Seq(commandSerializer) ++ additionalSerializers

    lazy val logContext = CustomContext()
  }

  trait SameSerializableCommand {
    _: ComponentT =>
    override type SerializableCommand = Command
  }

  case class Projection[Event, ComponentContextT <: ComponentContext with ComponentContext.Projection](name: String, handler: PartialFunction[(Event, ComponentContextT), Future[Done]])

  private[components] object ComponentT {
    sealed trait EventSourcedT extends ComponentT with SignalHandlers {
      type Event

      val eventSerializer: CirceSerializer[Event]

      type State

      override def serializers = super.serializers :+ eventSerializer
    }

    object EventSourcedT {
      sealed trait SnapshotsT extends EventSourcedT {
        val retentionCriteria: RetentionCriteria

        val stateSerializer: CirceSerializer[State]

        override def serializers = super.serializers :+ stateSerializer
      }
    }
  }

  abstract class ComponentContext extends Logging {
    protected val loggerClass: String
  }

  object ComponentContext {
    trait Actor[Command] extends ComponentContext {
      val actorContext: ActorContext[Command]

      protected override def logContext = super.logContext + CustomContext(
        "actorPath" -> actorContext.self.path.toStringWithoutAddress,
        "actorName" -> actorContext.self.path.name
      )
    }

    trait EventSourced extends ComponentContext {
      val persistenceId: PersistenceId

      protected override def logContext = super.logContext + CustomContext(
        "persistenceId" -> persistenceId.id
      )
    }

    trait Sharded[SerializableCommand, EntityId] extends ComponentContext {
      val entityId: EntityId

      def entityRef(entityId: EntityId): EntityRef[SerializableCommand]

      private[components] val entityIdCodec: EntityIdCodec[EntityId]

      protected override def logContext = super.logContext + entityIdCodec.logContext(entityId)
    }

    trait Projection extends ComponentContext with EventSourced {
      val name: String

      protected override def logContext = super.logContext + CustomContext(
        "projectionName" -> name
      )
    }
  }

  private[components] sealed trait InnerComponent[
    InnerComponentT <: InnerComponent[InnerComponentT]
  ] {
    self =>

    private[components] type Command
    private[components] type SerializableCommand <: Command

    private[components] type ComponentContextS <: ComponentContext
    private[components] type BehaviorS <: Behavior[Command]

    val behavior: ComponentContextS with ComponentContext.Actor[Command] => BehaviorS

    private[components] def behaviorTransformer: (ComponentContextS with ComponentContext.Actor[Command], BehaviorS) => BehaviorS = (_, behavior) => behavior

    private[components] final val transformedBehavior: ComponentContextS with ComponentContext.Actor[Command] => BehaviorS = context => behaviorTransformer(context, behavior(context))

    private[components] lazy val managedProjections: Seq[ManagedProjection[_, _]] = Nil

    private[components] def initProjections() = managedProjections.foreach(_.init())

    def init(): Component[InnerComponentT]

    private[components] def generateLoggerClass(implicit classTag: ClassTag[InnerComponentT]) = classTag.runtimeClass.getName.takeWhile(_ != '$')
  }

  object InnerComponent {
    sealed trait EventSourced[InnerComponentT <: InnerComponent[InnerComponentT]] extends InnerComponent[InnerComponentT] with SignalHandlers {
      self =>
      private[components] type Event
      private[components] type State

      override private[components] type BehaviorS = EventSourcedBehavior[Command, Event, State]

      override private[components] def behaviorTransformer = (context, behavior) =>
        super.behaviorTransformer(context, behavior)
          .withTagger(_ => Set(generateTag(context)))
          .onPersistFailure(SupervisorStrategy.restartWithBackoff(1.second, 1.minute, 0.2))
          // TODO test logging position
          .receiveSignal(defaultSignalHandler(context.log, implicitly))

      val tagGenerator: TagGenerator

      def generateTag(context: ComponentContextS): String

      val projections: Seq[Projection[Event, ComponentContextS with ComponentContext.Projection]] = Nil
    }

    object EventSourced {
      // marker trait for testkit as the State serialization shouldn't be verified in this case
      sealed trait WithoutSnapshots

      sealed trait WithSnapshots[InnerComponentT <: InnerComponent[InnerComponentT]] extends EventSourced[InnerComponentT] {
        val retentionCriteria: RetentionCriteria

        override private[components] def behaviorTransformer = (context, behavior) => super.behaviorTransformer(context, behavior).withRetention(retentionCriteria)
      }
    }
  }

  sealed trait Component[InnerComponentT <: InnerComponent[InnerComponentT]] {
    private[components] val component: InnerComponentT

    private[components] val serializers: Seq[CirceSerializer[_]]

    private[components] val managedProjections: Seq[ManagedProjection[_, _]]
  }

  sealed trait SingletonComponent[InnerComponentT <: InnerSingletonComponent[InnerComponentT]] extends Component[InnerComponentT] {
    val actorRef: ActorRef[component.SerializableCommand]
  }

  object Singleton {
    private[components] sealed trait InnerSingletonComponent[InnerComponentT <: InnerSingletonComponent[InnerComponentT]] extends InnerComponent[InnerComponentT]

    private[components] trait InnerSingletonComponentContext[InnerComponentT <: InnerSingletonComponent[InnerComponentT]] {
      _: InnerSingletonComponent[InnerComponentT] =>
      override private[components] type ComponentContextS = ComponentContext
    }

    private[components] sealed trait SingletonT extends ComponentT {
      outerSelf =>
      val clusterSingletonSettings: ClusterSingletonSettings => ClusterSingletonSettings = identity

      private[components] abstract class BaseComponentT[InnerComponentT <: BaseComponentT[InnerComponentT]](implicit actorSystem: ActorSystem[_]) extends InnerSingletonComponent[InnerComponentT] {
        self: InnerComponentT =>

        private[components] lazy val clusterSingleton: ClusterSingleton = ClusterSingleton(actorSystem)

        override private[components] type Command = outerSelf.Command

        override private[components] type SerializableCommand = outerSelf.SerializableCommand

        private[components] def fromActorContext(actorContext: ActorContext[Command]): ComponentContextS with ComponentContext.Actor[Command]

        override final def init() = {
          initProjections()

          new SingletonComponent[InnerComponentT] {
            override val component = self

            override val actorRef = clusterSingleton.init(SingletonActor(
              Behaviors
                .supervise(Behaviors.setup[Command](fromActorContext(_).pipe(transformedBehavior)).narrow[SerializableCommand])
                .onFailure(SupervisorStrategy.restartWithBackoff(1.second, 5.minute, 0.2)),
              name
            ).withSettings(clusterSingletonSettings(ClusterSingletonSettings(actorSystem))))

            override private[components] val serializers = outerSelf.serializers

            override private[components] val managedProjections = self.managedProjections
          }
        }
      }
    }

    abstract class EventSourced extends Singleton.SingletonT with ComponentT.EventSourcedT {
      outerSelf =>
      abstract class BaseComponent[InnerComponentT <: BaseComponent[InnerComponentT] : ClassTag](implicit actorSystem: ActorSystem[_]) extends BaseComponentT[InnerComponentT] with InnerComponent.EventSourced[InnerComponentT] with EventSourced.InnerSingletonEventSourcedComponentContext[InnerComponentT] {
        self: InnerComponentT =>

        override private[components] type ComponentContextS = ComponentContext with ComponentContext.EventSourced

        override private[components] type BehaviorS = EventSourcedBehavior[Command, Event, State]

        override private[components] type Event = outerSelf.Event
        override private[components] type State = outerSelf.State

        private val persistenceId = PersistenceId.ofUniqueId(name)

        override val tagGenerator = TagGenerator(name, 1)

        def generateTag(context: ComponentContextS) = tagGenerator.generateTag(0)

        override def fromActorContext(_actorContext: ActorContext[Command]) = new ComponentContext with ComponentContext.Actor[Command] with ComponentContext.EventSourced {
          override val persistenceId = self.persistenceId
          override val actorContext = _actorContext
          override protected lazy val loggerClass = generateLoggerClass

          override protected def logContext = super.logContext + outerSelf.logContext
        }

        override private[components] lazy val managedProjections = projections.map(projection => new ManagedProjection[Event, String](projection.name, tagGenerator, identity) {
          override implicit val actorSystem = self.actorSystem

          override def handle = projection.handler.compose {
            case (event, _) => event -> new ComponentContext with ComponentContext.EventSourced with ComponentContext.Projection {
              override val persistenceId = self.persistenceId
              override val name = projection.name
              override protected lazy val loggerClass = generateLoggerClass

              protected override def logContext = super.logContext + outerSelf.logContext
            }
          }
        })
      }
    }

    object EventSourced {
      private[components] trait InnerSingletonEventSourcedComponentContext[InnerComponentT <: InnerSingletonComponent[InnerComponentT]] {
        _: InnerSingletonComponent[InnerComponentT] =>
        override private[components] type ComponentContextS = ComponentContext with ComponentContext.EventSourced
      }

      abstract class WithSnapshots extends EventSourced with ComponentT.EventSourcedT.SnapshotsT {
        outerSelf =>
        abstract class BaseComponent[InnerComponentT <: BaseComponent[InnerComponentT] : ClassTag](implicit actorSystem: ActorSystem[_]) extends super.BaseComponent[InnerComponentT] with InnerComponent.EventSourced.WithSnapshots[InnerComponentT] {
          _: InnerComponentT =>
          override val retentionCriteria = outerSelf.retentionCriteria
        }
      }
    }
  }

  abstract class Singleton extends Singleton.SingletonT {
    outerSelf =>
    abstract class BaseComponent[InnerComponentT <: BaseComponentT[InnerComponentT] : ClassTag](implicit actorSystem: ActorSystem[_]) extends BaseComponentT[InnerComponentT] with Singleton.InnerSingletonComponentContext[InnerComponentT] {
      self: InnerComponentT =>
      override private[components] type BehaviorS = Behavior[Command]

      override def fromActorContext(_actorContext: ActorContext[Command]) = new ComponentContext with ComponentContext.Actor[Command] {
        override val actorContext = _actorContext
        override protected lazy val loggerClass = generateLoggerClass

        protected override def logContext = super.logContext + outerSelf.logContext
      }
    }
  }

  sealed trait ShardedComponent[InnerComponentT <: InnerShardedComponent[InnerComponentT]] extends Component[InnerComponentT] {
    def entityRef(entityId: component.EntityId): EntityRef[component.SerializableCommand]
  }

  object Sharded {
    trait EntityIdCodec[T] {
      def encode(entityId: T): String

      def decode(entityId: String): T

      def logContext(entityId: T) = CustomContext(
        "entityId" -> encode(entityId)
      )
    }

    object EntityIdCodec {
      def apply[T](_encode: T => String, _decode: String => T, _logContext: T => CustomContext = (_: T) => CustomContext()) = new EntityIdCodec[T] {
        override def encode(entityId: T) = _encode(entityId)

        override def decode(entityId: String) = _decode(entityId)

        override def logContext(entityId: T) = super.logContext(entityId) + _logContext(entityId)
      }
    }

    implicit val entityIdStringCodec = new EntityIdCodec[String] {
      override def encode(entityId: String) = entityId

      override def decode(entityId: String) = entityId
    }

    trait StringEntityId {
      _: ShardedT =>

      override type EntityId = String
    }

    private[components] sealed trait InnerShardedComponent[InnerComponentT <: InnerShardedComponent[InnerComponentT]] extends InnerComponent[InnerComponentT] {
      type EntityId

      val typeKey: EntityTypeKey[SerializableCommand]
    }

    private[components] trait InnerShardedComponentContext[InnerComponentT <: InnerShardedComponent[InnerComponentT]] {
      _: InnerShardedComponent[InnerComponentT] =>
      override type ComponentContextS = ComponentContext with ComponentContext.Sharded[SerializableCommand, EntityId]
    }

    private[components] sealed trait ShardedT extends ComponentT {
      outerSelf =>
      type EntityId

      val typeKey: EntityTypeKey[SerializableCommand]

      def generateTypeKey(implicit classTag: ClassTag[SerializableCommand]) = EntityTypeKey[SerializableCommand](name)

      val clusterShardingSettings: ClusterShardingSettings => ClusterShardingSettings = identity

      private[components] abstract class BaseComponentT[InnerComponentT <: BaseComponentT[InnerComponentT]](implicit actorSystem: ActorSystem[_], entityIdCodec: EntityIdCodec[EntityId]) extends InnerShardedComponent[InnerComponentT] {
        self: InnerComponentT =>

        override type EntityId = outerSelf.EntityId

        override lazy val typeKey = outerSelf.typeKey

        private[components] lazy val clusterSharding: ClusterSharding = ClusterSharding(actorSystem)

        override private[components] type Command = outerSelf.Command

        override private[components] type SerializableCommand = outerSelf.SerializableCommand

        def fromActorContext(actorContext: ActorContext[Command], entityId: EntityId): ComponentContextS with ComponentContext.Actor[Command]

        override final def init() = {
          initProjections()

          new ShardedComponent[InnerComponentT] {
            clusterSharding.init(Entity(typeKey)(entityContext =>
              Behaviors.supervise(
                Behaviors.setup[Command](_actorContext => transformedBehavior(fromActorContext(_actorContext, entityIdCodec.decode(entityContext.entityId)))).narrow[SerializableCommand]
              ).onFailure(SupervisorStrategy.restartWithBackoff(1.second, 5.minute, 0.2)),
            ).withSettings(clusterShardingSettings(ClusterShardingSettings(actorSystem))))

            override private[components] val component = self

            override def entityRef(entityId: EntityId) = clusterSharding.entityRefFor(typeKey, entityIdCodec.encode(entityId))

            override private[components] val serializers = outerSelf.serializers

            override private[components] val managedProjections = self.managedProjections
          }
        }
      }
    }

    abstract class EventSourced extends ShardedT with ComponentT.EventSourcedT {
      outerSelf =>
      abstract class BaseComponent[InnerComponentT <: BaseComponent[InnerComponentT] : ClassTag](implicit actorSystem: ActorSystem[_], _entityIdCodec: EntityIdCodec[outerSelf.EntityId]) extends BaseComponentT[InnerComponentT] with InnerComponent.EventSourced[InnerComponentT] with EventSourced.InnerShardedEventSourcedComponentContext[InnerComponentT] {
        self: InnerComponentT =>

        override private[components] type BehaviorS = EventSourcedBehavior[Command, Event, State]

        override private[components] type Event = outerSelf.Event
        override private[components] type State = outerSelf.State

        override val tagGenerator = TagGenerator(name, 1)

        override def generateTag(context: ComponentContextS) = tagGenerator.generateTag(_entityIdCodec.encode(context.entityId))

        override def fromActorContext(_actorContext: ActorContext[Command], _entityId: EntityId) = new ComponentContext with ComponentContext.Actor[Command] with ComponentContext.Sharded[SerializableCommand, EntityId] with ComponentContext.EventSourced {
          override val entityId = _entityId
          override val persistenceId = PersistenceId(typeKey.name, _entityIdCodec.encode(entityId))
          override val actorContext = _actorContext

          override def entityRef(entityId: EntityId) = clusterSharding.entityRefFor(typeKey, _entityIdCodec.encode(entityId))

          override protected lazy val loggerClass = generateLoggerClass

          protected override def logContext = super.logContext + outerSelf.logContext

          override private[components] val entityIdCodec = _entityIdCodec
        }

        override private[components] lazy val managedProjections = projections.map(projection => new ManagedProjection[Event, EntityId](projection.name, tagGenerator, _entityIdCodec.decode) {
          override implicit val actorSystem = self.actorSystem

          override def handle = projection.handler.compose {
            case (event, _entityId) => event -> new ComponentContext with ComponentContext.Sharded[SerializableCommand, EntityId] with ComponentContext.Projection {
              override val entityId = _entityId

              override def entityRef(entityId: EntityId) = clusterSharding.entityRefFor(typeKey, _entityIdCodec.encode(entityId))

              override private[components] val entityIdCodec = _entityIdCodec
              override val name = projection.name
              override val persistenceId = PersistenceId(typeKey.name, _entityIdCodec.encode(entityId))

              override protected lazy val loggerClass = generateLoggerClass

              protected override def logContext = super.logContext + outerSelf.logContext
            }
          }
        })
      }
    }

    object EventSourced {
      private[components] trait InnerShardedEventSourcedComponentContext[InnerComponentT <: InnerShardedComponent[InnerComponentT]] {
        _: InnerShardedComponent[InnerComponentT] =>
        override type ComponentContextS = ComponentContext with ComponentContext.Sharded[SerializableCommand, EntityId] with ComponentContext.EventSourced
      }

      abstract class WithSnapshots extends EventSourced with ComponentT.EventSourcedT.SnapshotsT {
        outerSelf =>
        abstract class BaseComponent[InnerComponentT <: BaseComponent[InnerComponentT] : ClassTag](implicit actorSystem: ActorSystem[_], _entityIdCodec: EntityIdCodec[outerSelf.EntityId]) extends super.BaseComponent[InnerComponentT] with InnerComponent.EventSourced.WithSnapshots[InnerComponentT] {
          self: InnerComponentT =>

          override val retentionCriteria = outerSelf.retentionCriteria
        }
      }
    }
  }

  abstract class Sharded extends Sharded.ShardedT {
    outerSelf =>

    abstract class BaseComponent[InnerComponentT <: BaseComponentT[InnerComponentT] : ClassTag](implicit actorSystem: ActorSystem[_], _entityIdCodec: EntityIdCodec[outerSelf.EntityId]) extends BaseComponentT[InnerComponentT] with Sharded.InnerShardedComponentContext[InnerComponentT] {
      self: InnerComponentT =>

      override private[components] type BehaviorS = Behavior[Command]

      override def fromActorContext(_actorContext: ActorContext[Command], _entityId: outerSelf.EntityId) = new ComponentContext with ComponentContext.Actor[Command] with ComponentContext.Sharded[SerializableCommand, outerSelf.EntityId] {

        override val entityId = _entityId
        override val actorContext = _actorContext

        override def entityRef(entityId: outerSelf.EntityId) = clusterSharding.entityRefFor(typeKey, _entityIdCodec.encode(entityId))

        override protected lazy val loggerClass = generateLoggerClass

        override protected def logContext = super.logContext + outerSelf.logContext

        override val entityIdCodec = _entityIdCodec
      }
    }
  }
}
