package net.sc8s.akka.components

import ClusterComponent.Sharded.EntityIdCodec

import akka.Done
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import akka.cluster.typed.{ClusterSingleton, ClusterSingletonSettings, SingletonActor}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, RetentionCriteria}
import com.lightbend.lagom.scaladsl.server.LagomApplication
import com.softwaremill.macwire.wireSet
import izumi.logstage.api.IzLogger
import izumi.logstage.api.Log.CustomContext
import net.sc8s.akka.circe.{CirceSerializer, CirceSerializerRegistry}
import net.sc8s.akka.persistence.utils.SignalHandlers
import net.sc8s.akka.projection.ProjectionUtils.{ManagedProjection, TagGenerator}
import net.sc8s.akka.projection.lagom.ProjectionComponents
import net.sc8s.lagom.circe.CirceAkkaSerializationComponents
import net.sc8s.logstage.elastic.Logging

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.implicitConversions
import scala.reflect.ClassTag
import scala.util.chaining.scalaUtilChainingOps

object ClusterComponent {
  abstract class ComponentContext {
    val log: IzLogger

    private[components] def logContext = CustomContext()
  }

  object ComponentContext {
    trait Actor[Command] extends ComponentContext {
      val actorContext: ActorContext[Command]

      override private[components] def logContext = super.logContext + CustomContext(
        "actorPath" -> actorContext.self.path.toStringWithoutAddress,
        "actorName" -> actorContext.self.path.name
      )
    }

    trait EventSourced extends ComponentContext {
      val persistenceId: PersistenceId

      override private[components] def logContext = super.logContext + CustomContext(
        "persistenceId" -> persistenceId.id
      )
    }

    trait Sharded[SerializableCommand, EntityId] extends ComponentContext {
      val entityId: EntityId

      def entityRef(entityId: EntityId): EntityRef[SerializableCommand]

      private[components] val entityIdCodec: EntityIdCodec[EntityId]

      private[components] override def logContext = super.logContext + entityIdCodec.logContext(entityId)
    }

    trait Projection extends ComponentContext with EventSourced {
      val name: String

      override private[components] def logContext = super.logContext + CustomContext(
        "projectionName" -> name
      )
    }
  }

  case class Projection[Event, ComponentContextT <: ComponentContext with ComponentContext.Projection](name: String, handler: PartialFunction[(Event, ComponentContextT), Future[Done]])

  private[components] abstract class ComponentT[
    Command: ClassTag,
    SerializableCommand <: Command,
    ComponentContextT <: ComponentContext,
  ] extends Logging {

    val name: String

    type BehaviorS <: Behavior[Command]

    type ComponentContextS <: ComponentContextT

    val behavior: ComponentContextS with ComponentContext.Actor[Command] => BehaviorS

    val commandSerializer: CirceSerializer[SerializableCommand]

    val actorSystem: ActorSystem[_]

    private[components] val behaviorTransformer: (ComponentContextS with ComponentContext.Actor[Command], BehaviorS) => BehaviorS = (_, behavior) => behavior

    private[components] val transformedBehavior: ComponentContextS with ComponentContext.Actor[Command] => BehaviorS = context => behaviorTransformer(context, behavior(context))

    val additionalSerializers: Seq[CirceSerializer[_]]

    private[components] def serializers: Seq[CirceSerializer[_]] = Seq(commandSerializer) ++ additionalSerializers

    private[components] val managedProjections: Seq[ManagedProjection[_, _]] = Nil

    private[components] def initProjections() = managedProjections.foreach(_.init())

    override lazy val loggerClass = implicitly[ClassTag[Command]].runtimeClass.getName.takeWhile(_ != '$')
  }

  private[components] object ComponentT {
    sealed trait EventSourced[
      Command,
      SerializableCommand <: Command,
      Event,
      State,
      ComponentContextT <: ComponentContext with ComponentContext.EventSourced,
    ] extends ComponentT[Command, SerializableCommand, ComponentContextT] with SignalHandlers {
      val eventSerializer: CirceSerializer[Event]

      override private[components] def serializers = super.serializers :+ eventSerializer

      override type BehaviorS = EventSourcedBehavior[Command, Event, State]

      type EventSourcedS <: EventSourced[Command, SerializableCommand, Event, State, ComponentContextS]

      override private[components] val behaviorTransformer = (context, behavior) =>
        behavior
          .withTagger(_ => Set(generateTag(context)))
          .onPersistFailure(SupervisorStrategy.restartWithBackoff(1.second, 1.minute, 0.2))
          .receiveSignal(defaultSignalHandler)

      val tagGenerator: TagGenerator

      def generateTag(context: ComponentContextS): String

      val projections: Seq[Projection[Event, ComponentContextS with ComponentContext.Projection]]
    }

    object EventSourced {
      sealed trait Snapshots[Command, SerializableCommand <: Command, Event, State, ComponentContextT <: ComponentContext with ComponentContext.EventSourced] extends EventSourced[Command, SerializableCommand, Event, State, ComponentContextT] {
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
    ComponentContextT <: ComponentContext
  ] extends ComponentT[Command, SerializableCommandT, ComponentContextT] {
    self =>
    lazy val clusterSingleton: ClusterSingleton = ClusterSingleton(actorSystem)

    val clusterSingletonSettings: ClusterSingletonSettings => ClusterSingletonSettings

    override type ComponentContextS = ComponentContextT

    def fromActorContext(actorContext: ActorContext[Command]): ComponentContextS with ComponentContext.Actor[Command]

    def init(): SingletonComponent[SerializableCommandT] = {
      initProjections()

      new SingletonComponent[SerializableCommandT] {

        override val actorRef = clusterSingleton.init(SingletonActor(
          Behaviors
            .supervise(Behaviors.setup[Command](fromActorContext(_).pipe(transformedBehavior)).narrow[SerializableCommandT])
            .onFailure(SupervisorStrategy.restartWithBackoff(1.second, 5.minute, 0.2)),
          name
        ).withSettings(clusterSingletonSettings(ClusterSingletonSettings(actorSystem))))

        override private[components] val component = self
      }
    }
  }

  case class Singleton[Command: ClassTag, SerializableCommand <: Command](
                                                                           name: String,
                                                                           behavior: ComponentContext with ComponentContext.Actor[Command] => Behavior[Command],
                                                                           commandSerializer: CirceSerializer[SerializableCommand],
                                                                           override val logContext: CustomContext = CustomContext(),
                                                                           clusterSingletonSettings: ClusterSingletonSettings => ClusterSingletonSettings = identity,
                                                                           override val additionalSerializers: Seq[CirceSerializer[_]] = Nil
                                                                         )(implicit val actorSystem: ActorSystem[_]) extends SingletonT[Command, SerializableCommand, ComponentContext] {
    self =>

    override def fromActorContext(_actorContext: ActorContext[Command]) = new ComponentContext with ComponentContext.Actor[Command] {
      override val actorContext = _actorContext

      override def logContext = super.logContext + self.logContext

      override lazy val log = self.log.withCustomContext(logContext)
    }

    override type BehaviorS = Behavior[Command]
  }

  object Singleton {

    private[components] abstract class EventSourcedT[Command: ClassTag, SerializableCommand <: Command, Event, State] extends SingletonT[Command, SerializableCommand, ComponentContext with ComponentContext.EventSourced] with ComponentT.EventSourced[Command, SerializableCommand, Event, State, ComponentContext with ComponentContext.EventSourced] {
      self =>
      implicit val actorSystem: ActorSystem[_]

      private val persistenceId = PersistenceId.ofUniqueId(name)

      override def fromActorContext(_actorContext: ActorContext[Command]) = new ComponentContext with ComponentContext.Actor[Command] with ComponentContext.EventSourced {
        override val persistenceId = self.persistenceId
        override val actorContext = _actorContext

        override lazy val log = self.log.withCustomContext(logContext)

        override def logContext = super.logContext + self.logContext
      }

      override type ComponentContextS = ComponentContext with ComponentContext.EventSourced

      override type EventSourcedS = EventSourcedT[Command, SerializableCommand, Event, State]

      lazy val tagGenerator = TagGenerator(name, 1)

      override private[components] val managedProjections = projections.map(projection => new ManagedProjection[Event, String](projection.name, tagGenerator, identity) {
        override implicit val actorSystem = self.actorSystem

        override def handle = projection.handler.compose {
          case (event, _) => event -> new ComponentContext with ComponentContext.EventSourced with ComponentContext.Projection {
            override val log = self.log.withCustomContext(logContext)
            override val persistenceId = self.persistenceId
            override val name = projection.name
          }
        }
      })

      override def generateTag(context: EventSourcedT.this.ComponentContextS) = tagGenerator.generateTag(0)
    }

    case class EventSourced[Command: ClassTag, SerializableCommand <: Command, Event, State](
                                                                                              name: String,
                                                                                              behavior: ComponentContext with ComponentContext.Actor[Command] with ComponentContext.EventSourced => EventSourcedBehavior[Command, Event, State],
                                                                                              commandSerializer: CirceSerializer[SerializableCommand],
                                                                                              eventSerializer: CirceSerializer[Event],
                                                                                              projections: Seq[Projection[Event, ComponentContext with ComponentContext.EventSourced with ComponentContext.Projection]] = Nil,
                                                                                              override val logContext: CustomContext = CustomContext(),
                                                                                              clusterSingletonSettings: ClusterSingletonSettings => ClusterSingletonSettings = identity,
                                                                                              override val additionalSerializers: Seq[CirceSerializer[_]] = Nil
                                                                                            )(implicit val actorSystem: ActorSystem[_]) extends EventSourcedT[Command, SerializableCommand, Event, State]

    object EventSourced {
      case class WithSnapshots[Command: ClassTag, SerializableCommand <: Command, Event, State] private[components](
                                                                                                                     name: String,
                                                                                                                     behavior: ComponentContext with ComponentContext.Actor[Command] with ComponentContext.EventSourced => EventSourcedBehavior[Command, Event, State],
                                                                                                                     commandSerializer: CirceSerializer[SerializableCommand],
                                                                                                                     eventSerializer: CirceSerializer[Event],
                                                                                                                     stateSerializer: CirceSerializer[State],
                                                                                                                     retentionCriteria: RetentionCriteria,
                                                                                                                     projections: Seq[Projection[Event, ComponentContext with ComponentContext.EventSourced with ComponentContext.Projection]] = Nil,
                                                                                                                     override val logContext: CustomContext = CustomContext(),
                                                                                                                     clusterSingletonSettings: ClusterSingletonSettings => ClusterSingletonSettings = identity,
                                                                                                                     override val additionalSerializers: Seq[CirceSerializer[_]] = Nil
                                                                                                                   )(implicit val actorSystem: ActorSystem[_]) extends EventSourcedT[Command, SerializableCommand, Event, State] with ComponentT.EventSourced.Snapshots[Command, SerializableCommand, Event, State, ComponentContext with ComponentContext.EventSourced]
    }
  }

  private[components] type ShardedComponentContext[Command, SerializableCommand <: Command, EntityId] = ComponentContext with ComponentContext.Sharded[SerializableCommand, EntityId]

  trait ShardedComponent[SerializableCommand, EntityId] extends Component[SerializableCommand] {
    def entityRef(entityId: EntityId): EntityRef[SerializableCommand]
  }

  private[components] abstract class ShardedT[Command: ClassTag, SerializableCommand <: Command, EntityId, ComponentContextT <: ShardedComponentContext[Command, SerializableCommand, EntityId]] extends ComponentT[Command, SerializableCommand, ComponentContextT] {
    self =>

    override type ComponentContextS = ComponentContextT

    val typeKey: EntityTypeKey[SerializableCommand] = EntityTypeKey[Command](name)

    lazy val clusterSharding: ClusterSharding = ClusterSharding(actorSystem)

    val clusterShardingSettings: ClusterShardingSettings => ClusterShardingSettings

    val entityIdCodec: EntityIdCodec[EntityId]

    def fromActorContext(actorContext: ActorContext[Command], entityId: EntityId): ComponentContextS with ComponentContext.Actor[Command]

    def init(): ShardedComponent[SerializableCommand, EntityId] = {
      initProjections()

      new ShardedComponent[SerializableCommand, EntityId] {
        clusterSharding.init(Entity(typeKey)(entityContext =>
          Behaviors.setup[Command](_actorContext => transformedBehavior(fromActorContext(_actorContext, entityIdCodec.decode(entityContext.entityId)))).narrow[SerializableCommand]
        ).withSettings(clusterShardingSettings(ClusterShardingSettings(actorSystem))))

        override private[components] val component = self

        override def entityRef(entityId: EntityId) = clusterSharding.entityRefFor(typeKey, entityIdCodec.encode(entityId))
      }
    }
  }

  case class Sharded[Command: ClassTag, SerializableCommand <: Command, EntityId](
                                                                                   name: String,
                                                                                   behavior: ShardedComponentContext[Command, SerializableCommand, EntityId] => Behavior[Command],
                                                                                   commandSerializer: CirceSerializer[SerializableCommand],
                                                                                   override val logContext: CustomContext = CustomContext(),
                                                                                   clusterShardingSettings: ClusterShardingSettings => ClusterShardingSettings = identity,
                                                                                   override val additionalSerializers: Seq[CirceSerializer[_]] = Nil
                                                                                 )(implicit val entityIdCodec: EntityIdCodec[EntityId], val actorSystem: ActorSystem[_]) extends ShardedT[Command, SerializableCommand, EntityId, ShardedComponentContext[Command, SerializableCommand, EntityId]] {
    self =>

    override def fromActorContext(_actorContext: ActorContext[Command], _entityId: EntityId) = new ComponentContext with ComponentContext.Actor[Command] with ComponentContext.Sharded[SerializableCommand, EntityId] {
      override val entityId = _entityId
      override val actorContext = _actorContext

      override def entityRef(entityId: EntityId) = clusterSharding.entityRefFor(typeKey, entityIdCodec.encode(entityId))

      override private[components] def logContext = super.logContext + self.logContext

      override private[components] val entityIdCodec = self.entityIdCodec

      override lazy val log = self.log.withCustomContext(logContext)
    }

    override type BehaviorS = Behavior[Command]
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

    private[components] type ShardedEventSourcedComponentContext[SerializableCommand, EntityId] = ComponentContext with ComponentContext.Sharded[SerializableCommand, EntityId] with ComponentContext.EventSourced

    private[components] abstract class EventSourcedT[Command: ClassTag, SerializableCommand <: Command, Event, State, EntityId] extends ShardedT[Command, SerializableCommand, EntityId, ShardedEventSourcedComponentContext[SerializableCommand, EntityId]] with ComponentT.EventSourced[Command, SerializableCommand, Event, State, ShardedEventSourcedComponentContext[SerializableCommand, EntityId]] {
      self =>
      implicit val actorSystem: ActorSystem[_]

      override type EventSourcedS = EventSourcedT[Command, SerializableCommand, Event, State, EntityId]

      override type ComponentContextS = ShardedEventSourcedComponentContext[SerializableCommand, EntityId]

      override def fromActorContext(_actorContext: ActorContext[Command], _entityId: EntityId) = new ComponentContext with ComponentContext.Actor[Command] with ComponentContext.Sharded[SerializableCommand, EntityId] with ComponentContext.EventSourced {
        override val entityId = _entityId
        override val persistenceId = PersistenceId(typeKey.name, self.entityIdCodec.encode(entityId))
        override val actorContext = _actorContext

        override def entityRef(entityId: EntityId) = clusterSharding.entityRefFor(typeKey, entityIdCodec.encode(entityId))

        override private[components] def logContext = super.logContext + self.logContext

        override private[components] val entityIdCodec = self.entityIdCodec

        override lazy val log = self.log.withCustomContext(logContext)
      }

      lazy val tagGenerator = TagGenerator(name, 1)

      override private[components] val managedProjections = projections.map(projection => new ManagedProjection[Event, EntityId](projection.name, tagGenerator, entityIdCodec.decode) {
        override implicit val actorSystem = self.actorSystem

        override def handle = projection.handler.compose {
          case (event, _entityId) => event -> new ComponentContext with ComponentContext.Sharded[SerializableCommand, EntityId] with ComponentContext.Projection {
            override val log = self.log
            override val entityId = _entityId

            override def entityRef(entityId: EntityId) = clusterSharding.entityRefFor(typeKey, entityIdCodec.encode(entityId))

            override private[components] val entityIdCodec = self.entityIdCodec
            override val name = projection.name
            override val persistenceId = PersistenceId(typeKey.name, self.entityIdCodec.encode(entityId))
          }
        }
      })

      override def generateTag(context: ComponentContextS) = tagGenerator.generateTag(entityIdCodec.encode(context.entityId))
    }

    case class EventSourced[Command: ClassTag, SerializableCommand <: Command, Event, State, EntityId] private(
                                                                                                                name: String,
                                                                                                                behavior: ComponentContext with ComponentContext.Actor[Command] with ShardedEventSourcedComponentContext[SerializableCommand, EntityId] => EventSourcedBehavior[Command, Event, State],
                                                                                                                commandSerializer: CirceSerializer[SerializableCommand],
                                                                                                                eventSerializer: CirceSerializer[Event],
                                                                                                                projections: Seq[Projection[Event, ShardedEventSourcedComponentContext[SerializableCommand, EntityId] with ComponentContext.Projection]] = Nil,
                                                                                                                override val logContext: CustomContext = CustomContext(),
                                                                                                                clusterShardingSettings: ClusterShardingSettings => ClusterShardingSettings = identity,
                                                                                                                override val additionalSerializers: Seq[CirceSerializer[_]] = Nil
                                                                                                              )(implicit val entityIdCodec: EntityIdCodec[EntityId], val actorSystem: ActorSystem[_]) extends EventSourcedT[Command, SerializableCommand, Event, State, EntityId]

    object EventSourced {

      case class WithSnapshots[Command: ClassTag, SerializableCommand <: Command, Event, State, EntityId] private[components](
                                                                                                                               name: String,
                                                                                                                               behavior: ComponentContext with ComponentContext.Actor[Command] with ShardedEventSourcedComponentContext[SerializableCommand, EntityId] => EventSourcedBehavior[Command, Event, State],
                                                                                                                               commandSerializer: CirceSerializer[SerializableCommand],
                                                                                                                               eventSerializer: CirceSerializer[Event],
                                                                                                                               stateSerializer: CirceSerializer[State],
                                                                                                                               retentionCriteria: RetentionCriteria,
                                                                                                                               projections: Seq[Projection[Event, ShardedEventSourcedComponentContext[SerializableCommand, EntityId] with ComponentContext.Projection]] = Nil,
                                                                                                                               override val logContext: CustomContext = CustomContext(),
                                                                                                                               clusterShardingSettings: ClusterShardingSettings => ClusterShardingSettings = identity,
                                                                                                                               override val additionalSerializers: Seq[CirceSerializer[_]] = Nil
                                                                                                                             )(implicit val entityIdCodec: EntityIdCodec[EntityId], val actorSystem: ActorSystem[_]) extends EventSourcedT[Command, SerializableCommand, Event, State, EntityId] with ComponentT.EventSourced.Snapshots[Command, SerializableCommand, Event, State, ShardedEventSourcedComponentContext[SerializableCommand, EntityId]]
    }
  }
}
