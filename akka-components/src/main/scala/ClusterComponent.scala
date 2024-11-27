package net.sc8s.akka.components

import akka.Done
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Signal, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl._
import akka.cluster.sharding.typed.{ClusterShardingSettings, ShardingEnvelope}
import akka.cluster.typed.{ClusterSingleton, ClusterSingletonSettings, SingletonActor}
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, RetentionCriteria}
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.stream.Materializer
import io.circe.syntax.EncoderOps
import io.circe.{Codec, parser}
import izumi.fundamentals.platform.language.CodePositionMaterializer
import izumi.logstage.api.Log.CustomContext
import izumi.logstage.api.Log.Level.Info
import net.sc8s.akka.circe.CirceSerializer
import net.sc8s.akka.components.ClusterComponent.Sharded.EntityIdCodec
import net.sc8s.akka.components.persistence.projection.ManagedProjection
import net.sc8s.akka.persistence.utils.SignalHandlers
import net.sc8s.logstage.elastic.Logging

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag
import scala.util.chaining.scalaUtilChainingOps
import scala.util.{Success, Try}

object ClusterComponent {
  private[components] sealed trait ComponentT {
    outerSelf =>
    type Command

    type SerializableCommand <: Command

    type Wiring <: Component[outerSelf.type]

    private[components] trait BaseComponentT {
      private[components] type BehaviorS <: Behavior[outerSelf.Command]

      private[components] type ComponentContextS <: ComponentContext

      private[components] type BehaviorComponentContextS <: ComponentContextS with ComponentContext.Actor[outerSelf.Command]

      private[components] val behavior: BehaviorComponentContextS => BehaviorS

      // allows the user to wrap the behavior, especially useful to wrap EventSourcedBehavior in e.g. Behaviors.withTimers
      def wrapBehavior: BehaviorComponentContextS => BehaviorS => Behavior[Command] = _ => identity

      private[components] def behaviorTransformer: (BehaviorComponentContextS, BehaviorS) => BehaviorS = (_, behavior) => behavior

      final val transformedBehavior: BehaviorComponentContextS => Behavior[outerSelf.Command] =
        context => wrapBehavior(context)(behaviorTransformer(context, behavior(context)))

      // implicit classTag returned only these classes when used as an external dependency
      private[components] def generateLoggerClass(clazz: Class[_]) = clazz.getName.takeWhile(_ != '$')

      private[components] def managedProjections(implicit actorSystem: ActorSystem[_]): Seq[ManagedProjection[_]] = Nil

      private[components] val componentCodePositionMaterializer: CodePositionMaterializer
    }

    type BaseComponent <: BaseComponentT

    // this can't be moved into the BaseComponent itself as otherwise circular dependencies between components lead to initialization loops
    def init(component: => BaseComponent)(implicit actorSystem: => ActorSystem[_]): Wiring

    val name: String

    val additionalSerializers: Seq[CirceSerializer[_]] = Nil

    val commandSerializer: CirceSerializer[SerializableCommand]

    // leave it public for tests
    def serializers = Seq(commandSerializer) ++ additionalSerializers

    lazy val logContext = CustomContext()

    private[components] val componentCodePositionMaterializer: CodePositionMaterializer
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

      private[components] trait EventSourcedBaseComponentT extends super.BaseComponentT {

        // for mixin traits accessibility
        private[components] type EventT = Event
        private[components] val componentName = name

        override private[components] type BehaviorS = EventSourcedBehavior[Command, Event, State]

        override private[components] type ComponentContextS <: ComponentContext with ComponentContext.EventSourced

        override private[components] def behaviorTransformer = (context, behavior) => {
          val transformedBehavior = super.behaviorTransformer(context, behavior)
          transformedBehavior
            .withTagger(tagger(name, context))
            .onPersistFailure(SupervisorStrategy.restartWithBackoff(1.second, 1.minute, 0.2))
        }

        private[components] def tagger(name: String, context: ComponentContextS)(event: EventT) = Set.empty[String]

        private[components] def projectionContext(
                                                   projectionName: String,
                                                   persistenceId: PersistenceId,
                                                   actorSystem: ActorSystem[_]
                                                 ): ComponentContextS with ComponentContext.Projection
      }

      override def serializers = super.serializers :+ eventSerializer
    }

    object EventSourcedT {
      sealed trait SnapshotsT extends EventSourcedT {

        private[components] sealed trait SnapshotsBaseComponentT extends super.EventSourcedBaseComponentT {
          // for mixin traits accessibility
          private[components] type StateT = State

          override private[components] def behaviorTransformer = (context, behavior) => super.behaviorTransformer(context, behavior).withRetention(retentionCriteria)

          val retentionCriteria: RetentionCriteria
        }

        val stateSerializer: CirceSerializer[State]

        override def serializers = super.serializers :+ stateSerializer
      }

      trait ProjectionT {
        _: EventSourcedT#EventSourcedBaseComponentT =>

        // Set[_] so you can use `com.softwaremill.macwire#wireSet`
        val projections: Set[Projection[EventT, ComponentContextS with ComponentContext.Projection]]

        // helper method so you don't have to care about type parameters in case you create it outside of the member `projections`
        def createProjection(name: String)(handler: PartialFunction[(EventT, ComponentContextS with ComponentContext.Projection), Future[Done]]): Projection[EventT, ComponentContextS with ComponentContext.Projection] = Projection(name, handler)

        override private[components] def managedProjections(implicit actorSystem: ActorSystem[_]) = projections.map(managedProjectionFactory(_, actorSystem)).toSeq

        private[components] def managedProjectionFactory(
                                                          projection: Projection[EventT, ComponentContextS with ComponentContext.Projection],
                                                          actorSystem: ActorSystem[_]
                                                        ): ManagedProjection[_]
      }
    }
  }

  trait ComponentContext extends Logging {
    protected val loggerClass: String
  }

  object ComponentContext {
    trait Actor[Command] extends ComponentContext {
      val actorContext: ActorContext[Command]

      implicit lazy val materializer = Materializer(actorContext)

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

      def entityRefFor(entityId: EntityId): EntityRef[SerializableCommand]

      private[components] val entityIdCodec: EntityIdCodec[EntityId]

      private[components] val typeKey: EntityTypeKey[SerializableCommand]

      protected override def logContext = super.logContext + entityIdCodec.logContext(entityId) + CustomContext("typeKey" -> typeKey.name)
    }

    trait ShardedEntity[SerializableCommand] {
      _: Sharded[SerializableCommand, _] =>
      val entityContext: EntityContext[SerializableCommand]
    }

    trait Projection extends ComponentContext with EventSourced {
      val name: String

      // often needed in a projection anyway
      implicit val actorSystem: ActorSystem[_]

      protected override def logContext = super.logContext + CustomContext(
        "projectionName" -> name
      )
    }
  }

  sealed trait Component[OuterComponentT <: ComponentT] {
    private[components] val component: OuterComponentT

    private[components] val innerComponent: component.BaseComponent

    private[components] val serializers: Seq[CirceSerializer[_]]

    private[components] val managedProjections: Seq[ManagedProjection[_]]

    private[components] def delayedInit(): Unit = managedProjections.foreach(_.init())

    override def toString = s"ClusterComponent(name = ${component.name}, serializers = ${serializers.map(_.entityClass)})"
  }

  trait SingletonComponent[OuterComponentT <: Singleton.SingletonT] extends Component[OuterComponentT] {
    val actorRef: ActorRef[component.SerializableCommand]
  }

  private def supervised[T](behavior: Behavior[T]) =
    Behaviors
      .supervise(
        Behaviors.supervise(behavior).onFailure[SkipSupervisedRestartException](SupervisorStrategy.stop)
      ).onFailure(SupervisorStrategy.restartWithBackoff(1.second, 5.minute, 0.2))

  object Singleton {

    private[components] sealed trait SingletonT extends ComponentT {
      outerSelf =>
      val clusterSingletonSettings: ClusterSingletonSettings => ClusterSingletonSettings = identity

      override type Wiring = SingletonComponent[outerSelf.type]

      override type BaseComponent <: SingletonBaseComponentT

      override def init(_innerComponent: => BaseComponent)(implicit actorSystem: => ActorSystem[_]) = new SingletonComponent[outerSelf.type] {
        override private[components] val component = outerSelf

        override private[components] lazy val innerComponent = _innerComponent

        override lazy val actorRef = _innerComponent.actorRef(actorSystem)

        override private[components] def delayedInit() = {
          // eagerly initialize singleton
          actorRef
          super.delayedInit()
        }

        override private[components] val serializers = outerSelf.serializers

        override private[components] lazy val managedProjections = _innerComponent.managedProjections(actorSystem)
      }

      private[components] trait SingletonBaseComponentT extends super.BaseComponentT {
        self =>

        private[components] def actorRef(implicit actorSystem: ActorSystem[_]) = {
          val behavior = Behaviors.setup[Command] { actorContext =>
            val componentContext = fromActorContext(actorContext)
            initializationMessage(componentContext)
            transformedBehavior(componentContext)
          }.narrow[SerializableCommand]

          ClusterSingleton(actorSystem).init(
            singletonTransformation(SingletonActor(supervised(behavior), name))
              .withSettings(clusterSingletonSettings(ClusterSingletonSettings(actorSystem)))
          )
        }

        private[components] def fromActorContext(actorContext: ActorContext[Command]): BehaviorComponentContextS

        val singletonTransformation: SingletonActor[SerializableCommand] => SingletonActor[SerializableCommand] = identity

        override private[components] val componentCodePositionMaterializer = outerSelf.componentCodePositionMaterializer

        def initializationMessage(componentContext: BehaviorComponentContextS) = componentContext.log.log(Info)(s"${"initializing" -> "tag"}")(outerSelf.componentCodePositionMaterializer)
      }
    }

    abstract class EventSourced(implicit val componentCodePositionMaterializer: CodePositionMaterializer) extends SingletonT with ComponentT.EventSourcedT {
      outerSelf =>

      trait BaseComponent extends super.SingletonBaseComponentT with super.EventSourcedBaseComponentT {
        self =>

        override private[components] type ComponentContextS = ComponentContext with ComponentContext.EventSourced

        private[components] val persistenceId = PersistenceId.ofUniqueId(name)

        override private[components] type BehaviorComponentContextS = ComponentContext with ComponentContext.Actor[Command] with ComponentContext.EventSourced

        override def fromActorContext(_actorContext: ActorContext[Command]) = new ComponentContext with ComponentContext.Actor[Command] with ComponentContext.EventSourced {
          override val persistenceId = self.persistenceId
          override val actorContext = _actorContext
          override protected lazy val loggerClass = generateLoggerClass(outerSelf.getClass)

          override protected def logContext = super.logContext + outerSelf.logContext
        }

        override private[components] def projectionContext(
                                                            projectionName: String,
                                                            _persistenceId: PersistenceId,
                                                            _actorSystem: ActorSystem[_]
                                                          ) = new ComponentContext with ComponentContext.EventSourced with ComponentContext.Projection {
          override val persistenceId = _persistenceId
          override val name = projectionName
          override protected lazy val loggerClass = generateLoggerClass(outerSelf.getClass)
          override implicit val actorSystem = _actorSystem

          protected override def logContext = super.logContext + outerSelf.logContext
        }

        override private[components] def behaviorTransformer = (context, behavior) => {
          val transformedBehavior = super.behaviorTransformer(context, behavior)
          transformedBehavior.receiveSignal(
            withDefaultSignalHandler(transformedBehavior.signalHandler)(context.log, componentCodePositionMaterializer)
          )
        }
      }
    }

    object EventSourced {
      abstract class WithSnapshots(implicit override val componentCodePositionMaterializer: CodePositionMaterializer) extends EventSourced with ComponentT.EventSourcedT.SnapshotsT {
        outerSelf =>
        trait BaseComponent extends super.BaseComponent with SnapshotsBaseComponentT {
          // this looks odd (as it's the same as in super) but it helps IntelliJ pull the right ComponentContextS (and not the ohne from ClusterComponent.ComponentT.EventSourcedT.EventSourcedBaseComponentT)
          override private[components] type ComponentContextS = ComponentContext with ComponentContext.EventSourced
        }
      }
    }
  }

  abstract class Singleton(implicit val componentCodePositionMaterializer: CodePositionMaterializer) extends Singleton.SingletonT {
    outerSelf =>
    trait BaseComponent extends SingletonBaseComponentT {

      override private[components] type ComponentContextS = ComponentContext

      override private[components] type BehaviorS = Behavior[Command]

      override private[components] type BehaviorComponentContextS = ComponentContext with ComponentContext.Actor[Command]

      override def fromActorContext(_actorContext: ActorContext[Command]) = new ComponentContext with ComponentContext.Actor[Command] {
        override val actorContext = _actorContext

        override protected lazy val loggerClass = generateLoggerClass(outerSelf.getClass)

        protected override def logContext = super.logContext + outerSelf.logContext
      }
    }
  }

  trait ShardedComponent[OuterComponentT <: Sharded.ShardedT] extends Component[OuterComponentT] {
    def entityRefFor(entityId: component.EntityId): EntityRef[component.SerializableCommand]
  }

  object Sharded {
    trait EntityIdCodec[T] {
      def encode(entityId: T): String

      def decode(entityId: String): Try[T]

      def logContext(entityId: T) = CustomContext(
        "entityId" -> entityId
      )
    }

    object EntityIdCodec {
      def apply[T](_encode: T => String, _decode: String => Try[T], _logContext: T => CustomContext = (_: T) => CustomContext()) = new EntityIdCodec[T] {
        override def encode(entityId: T) = _encode(entityId)

        override def decode(entityId: String) = _decode(entityId)

        override def logContext(entityId: T) = super.logContext(entityId) + _logContext(entityId)
      }
    }

    trait StringEntityId {
      _: ShardedT =>

      override type EntityId = String

      override implicit val entityIdCodec = EntityIdCodec[String](identity, Success(_))
    }

    trait LongEntityId {
      _: ShardedT =>

      override type EntityId = Long

      override implicit val entityIdCodec: EntityIdCodec[Long] = EntityIdCodec[Long](_.toString, idString => Try(idString.toLong))
    }

    trait IntEntityId {
      _: ShardedT =>

      override type EntityId = Int

      override implicit val entityIdCodec: EntityIdCodec[Int] = EntityIdCodec[Int](_.toString, idString => Try(idString.toInt))
    }

    trait JsonEntityId {
      _: ShardedT =>

      /**
       * The placeholder that will replace any | in the produced String as this character is illegal due to Akka's entityId encoding.
       * When overriding keep the new placeholder permanently or existing entities won't be resolved.
       * */
      val barPlaceholder = "❘❘bar❘❘"

      implicit val entityIdCirceCodec: Codec[EntityId]

      implicit val entityIdCodec: EntityIdCodec[EntityId] = EntityIdCodec[EntityId](
        _.asJson.noSpacesSortKeys.replace("|", barPlaceholder),
        _.replace(barPlaceholder, "|").pipe(parser.parse).flatMap(_.as[EntityId]).toTry
      )
    }

    private[components] sealed trait ShardedT extends ComponentT {
      outerSelf =>

      override type Wiring = ShardedComponent[outerSelf.type]

      type EntityId

      implicit val entityIdCodec: EntityIdCodec[EntityId]

      // an initialized typeKey is available in the InnerComponent, in general you should not need this
      def generateTypeKey(implicit classTag: ClassTag[SerializableCommand]) = EntityTypeKey[SerializableCommand](name)

      override type BaseComponent <: ShardedBaseComponentT

      override def init(_innerComponent: => BaseComponent)(implicit actorSystem: => ActorSystem[_]) =
        new ShardedComponent[outerSelf.type] {
          override private[components] def delayedInit() = {
            _innerComponent.initSharding()(actorSystem)
            super.delayedInit()
          }

          override private[components] lazy val innerComponent = _innerComponent

          override private[components] val component = outerSelf

          override def entityRefFor(entityId: EntityId) = _innerComponent.entityRefFor(entityId)(actorSystem)

          override private[components] val serializers = outerSelf.serializers

          override private[components] lazy val managedProjections = _innerComponent.managedProjections(actorSystem)
        }

      private[components] abstract class ShardedBaseComponentT(implicit classTag: ClassTag[outerSelf.SerializableCommand]) extends super.BaseComponentT {
        self =>

        override private[components] type BehaviorComponentContextS = ComponentContextS with ComponentContext.Actor[Command] with ComponentContext.ShardedEntity[SerializableCommand]

        val typeKey: EntityTypeKey[SerializableCommand] = generateTypeKey

        def entityRefFor(entityId: EntityId)(implicit actorSystem: ActorSystem[_]) = {
          val clusterSharding: ClusterSharding = ClusterSharding(actorSystem)

          clusterSharding.entityRefFor(typeKey, entityIdCodec.encode(entityId))
        }

        private[components] def initSharding()(implicit actorSystem: ActorSystem[_]) = {
          val clusterSharding: ClusterSharding = ClusterSharding(actorSystem)

          val entity = Entity(typeKey)(entityContext =>
            supervised(Behaviors.setup[Command](actorContext => transformedBehavior(fromActorContext(
              actorContext,
              entityContext,
              entityIdCodec.decode(entityContext.entityId).get
            ))).narrow[SerializableCommand])
          ).withSettings(clusterShardingSettings(ClusterShardingSettings(actorSystem)))

          clusterSharding.init(entityTransformation(entity))
        }

        // shortcut to change clusterShardingSettings
        val clusterShardingSettings: ClusterShardingSettings => ClusterShardingSettings = identity

        // use this to transform the entity, e.g. `_.withStopMessage(...)`
        val entityTransformation: Entity[SerializableCommand, ShardingEnvelope[SerializableCommand]] => Entity[SerializableCommand, ShardingEnvelope[SerializableCommand]] = identity

        private[components] def fromActorContext(
                                                  actorContext: ActorContext[Command],
                                                  entityContext: EntityContext[SerializableCommand],
                                                  entityId: EntityId
                                                ): ComponentContextS with ComponentContext.Actor[Command] with ComponentContext.ShardedEntity[SerializableCommand]

        override private[components] val componentCodePositionMaterializer = outerSelf.componentCodePositionMaterializer
      }
    }

    abstract class EventSourced(implicit val componentCodePositionMaterializer: CodePositionMaterializer) extends ShardedT with ComponentT.EventSourcedT {
      outerSelf =>
      trait BaseComponent extends super.ShardedBaseComponentT with super.EventSourcedBaseComponentT {
        self =>

        override private[components] type ComponentContextS = ComponentContext with ComponentContext.Sharded[outerSelf.SerializableCommand, outerSelf.EntityId] with ComponentContext.EventSourced

        override def fromActorContext(
                                       _actorContext: ActorContext[Command],
                                       _entityContext: EntityContext[SerializableCommand],
                                       _entityId: EntityId
                                     ) = new ComponentContext with ComponentContext.Actor[Command] with ComponentContext.Sharded[SerializableCommand, EntityId] with ComponentContext.EventSourced with ComponentContext.ShardedEntity[SerializableCommand] {
          override val entityId = _entityId
          override val persistenceId = PersistenceId(self.typeKey.name, outerSelf.entityIdCodec.encode(entityId))
          override val actorContext = _actorContext
          override val entityContext = _entityContext

          private lazy val clusterSharding: ClusterSharding = ClusterSharding(actorContext.system)

          override def entityRefFor(entityId: EntityId) = clusterSharding.entityRefFor(typeKey, outerSelf.entityIdCodec.encode(entityId))

          override protected lazy val loggerClass = generateLoggerClass(outerSelf.getClass)

          protected override def logContext = super.logContext + outerSelf.logContext

          override private[components] val entityIdCodec = outerSelf.entityIdCodec

          override private[components] val typeKey = self.typeKey
        }

        override private[components] def projectionContext(
                                                            projectionName: String,
                                                            _persistenceId: PersistenceId,
                                                            _actorSystem: ActorSystem[_]
                                                          ) =
          new ComponentContext with ComponentContext.Sharded[SerializableCommand, EntityId] with ComponentContext.Projection {
            private lazy val clusterSharding: ClusterSharding = ClusterSharding(_actorSystem)

            override def entityRefFor(entityId: EntityId) = clusterSharding.entityRefFor(typeKey, outerSelf.entityIdCodec.encode(entityId))

            override private[components] val entityIdCodec = outerSelf.entityIdCodec

            override val entityId = _persistenceId.entityId.pipe(entityIdCodec.decode(_).get)

            override val name = projectionName
            override val persistenceId = _persistenceId
            override protected lazy val loggerClass = generateLoggerClass(outerSelf.getClass)
            override implicit val actorSystem = _actorSystem

            protected override def logContext = super.logContext + outerSelf.logContext

            override private[components] val typeKey = self.typeKey
          }

        override private[components] def behaviorTransformer = (context, behavior) => {
          val transformedBehavior = super.behaviorTransformer(context, behavior)
          transformedBehavior.receiveSignal(
            withDefaultSignalHandler(transformedBehavior.signalHandler.orElse {
              case (_, RecoveryCompleted) =>
              // don't log recovery for sharded components as there might be a lot
            }: PartialFunction[(State, Signal), Unit])(context.log, componentCodePositionMaterializer)
          )
        }
      }
    }

    object EventSourced {
      abstract class WithSnapshots(implicit override val componentCodePositionMaterializer: CodePositionMaterializer) extends EventSourced with ComponentT.EventSourcedT.SnapshotsT {
        outerSelf =>
        trait BaseComponent extends super.BaseComponent with SnapshotsBaseComponentT {
          // this looks odd (as it's the same as in super) but it helps IntelliJ pull the right ComponentContextS (and not the ohne from ClusterComponent.ComponentT.EventSourcedT.EventSourcedBaseComponentT)
          override type ComponentContextS = ComponentContext with ComponentContext.Sharded[outerSelf.SerializableCommand, outerSelf.EntityId] with ComponentContext.EventSourced
        }
      }
    }
  }

  abstract class Sharded(implicit val componentCodePositionMaterializer: CodePositionMaterializer) extends Sharded.ShardedT {
    outerSelf =>

    trait BaseComponent extends super.ShardedBaseComponentT {
      self =>
      override private[components] type ComponentContextS = ComponentContext with ComponentContext.Sharded[outerSelf.SerializableCommand, outerSelf.EntityId]

      override private[components] type BehaviorS = Behavior[Command]

      override def fromActorContext(
                                     _actorContext: ActorContext[Command],
                                     _entityContext: EntityContext[SerializableCommand],
                                     _entityId: EntityId
                                   ) = {
        lazy val clusterSharding: ClusterSharding = ClusterSharding(_actorContext.system)

        new ComponentContext with ComponentContext.Actor[Command] with ComponentContext.Sharded[SerializableCommand, EntityId] with ComponentContext.ShardedEntity[SerializableCommand] {
          override val entityId = _entityId
          override val actorContext = _actorContext
          override val entityContext = _entityContext

          override def entityRefFor(entityId: EntityId) = clusterSharding.entityRefFor(self.typeKey, outerSelf.entityIdCodec.encode(entityId))

          override protected lazy val loggerClass = generateLoggerClass(outerSelf.getClass)

          override protected def logContext = super.logContext + outerSelf.logContext

          override val entityIdCodec = outerSelf.entityIdCodec

          override private[components] val typeKey = self.typeKey
        }
      }
    }
  }

  // throw this to avoid actor restart by supervisor
  class SkipSupervisedRestartException(message: String) extends Throwable(message)
}
