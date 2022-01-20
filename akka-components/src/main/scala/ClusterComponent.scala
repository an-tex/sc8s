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
import akka.stream.Materializer
import io.circe.syntax.EncoderOps
import io.circe.{Codec, parser}
import izumi.fundamentals.platform.language.CodePositionMaterializer
import izumi.logstage.api.Log.CustomContext
import izumi.logstage.api.Log.Level.Info
import net.sc8s.akka.circe.CirceSerializer
import net.sc8s.akka.persistence.utils.SignalHandlers
import net.sc8s.akka.projection.ProjectionUtils.{ManagedProjection, TagGenerator}
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

      private[components] val behavior: ComponentContextS with ComponentContext.Actor[outerSelf.Command] => BehaviorS

      private[components] def behaviorTransformer: (ComponentContextS with ComponentContext.Actor[outerSelf.Command], BehaviorS) => BehaviorS = (_, behavior) => behavior

      final val transformedBehavior: ComponentContextS with ComponentContext.Actor[outerSelf.Command] => BehaviorS = context => behaviorTransformer(context, behavior(context))

      private[components] def generateLoggerClass(implicit classTag: ClassTag[outerSelf.type]) = classTag.runtimeClass.getName.takeWhile(_ != '$')

      private[components] def managedProjections(implicit actorSystem: ActorSystem[_]): Seq[ManagedProjection[_, _]] = Nil

      private[components] val componentCodePositionMaterializer: CodePositionMaterializer
    }

    type BaseComponent <: BaseComponentT

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

        override private[components] type BehaviorS = EventSourcedBehavior[Command, Event, State]

        override private[components] type ComponentContextS <: ComponentContext with ComponentContext.EventSourced

        override private[components] def behaviorTransformer = (context, behavior) => {
          val transformedBehavior = super.behaviorTransformer(context, behavior)
          transformedBehavior
            .withTagger(_ => Set(generateTag(context)))
            .onPersistFailure(SupervisorStrategy.restartWithBackoff(1.second, 1.minute, 0.2))
            .receiveSignal(withDefaultSignalHandler(transformedBehavior.signalHandler)(context.log, componentCodePositionMaterializer))
        }

        val tagGenerator: TagGenerator

        def generateTag(context: ComponentContextS): String

        val projections: Seq[Projection[Event, ComponentContextS with ComponentContext.Projection]] = Nil
      }

      override def serializers = super.serializers :+ eventSerializer
    }

    object EventSourcedT {
      sealed trait SnapshotsT extends EventSourcedT {
        val retentionCriteria: RetentionCriteria

        private[components] sealed trait SnapshotsBaseComponentT extends super.EventSourcedBaseComponentT {
          override private[components] def behaviorTransformer = (context, behavior) => super.behaviorTransformer(context, behavior).withRetention(retentionCriteria)
        }

        val stateSerializer: CirceSerializer[State]

        override def serializers = super.serializers :+ stateSerializer
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

      def entityRef(entityId: EntityId): EntityRef[SerializableCommand]

      private[components] val entityIdCodec: EntityIdCodec[EntityId]

      protected override def logContext = super.logContext + entityIdCodec.logContext(entityId)
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

    private[components] val serializers: Seq[CirceSerializer[_]]

    private[components] val managedProjections: Seq[ManagedProjection[_, _]]

    private[components] def delayedInit(): Unit = managedProjections.foreach(_.init())

    override def toString = s"ClusterComponent(name = ${component.name}, serializers = ${serializers.map(_.entityClass)})"
  }

  trait SingletonComponent[OuterComponentT <: Singleton.SingletonT] extends Component[OuterComponentT] {
    val actorRef: ActorRef[component.SerializableCommand]
  }

  object Singleton {

    private[components] sealed trait SingletonT extends ComponentT {
      outerSelf =>
      val clusterSingletonSettings: ClusterSingletonSettings => ClusterSingletonSettings = identity

      override type Wiring = SingletonComponent[outerSelf.type]

      override type BaseComponent <: SingletonBaseComponentT

      override def init(innerComponent: => BaseComponent)(implicit actorSystem: => ActorSystem[_]) = new SingletonComponent[outerSelf.type] {
        override private[components] val component = outerSelf

        override lazy val actorRef = innerComponent.actorRef(actorSystem)

        override private[components] def delayedInit() = {
          // eagerly initialize singleton
          actorRef
          super.delayedInit()
        }

        override private[components] val serializers = outerSelf.serializers

        override private[components] lazy val managedProjections = innerComponent.managedProjections(actorSystem)
      }

      private[components] trait SingletonBaseComponentT extends super.BaseComponentT {
        self =>

        private[components] def actorRef(implicit actorSystem: ActorSystem[_]) = ClusterSingleton(actorSystem).init(SingletonActor(
          Behaviors
            .supervise(Behaviors.setup[Command] { actorContext =>
              val componentContext = fromActorContext(actorContext)
              componentContext.log.log(Info)(s"${"initializing" -> "tag"}")(outerSelf.componentCodePositionMaterializer)
              transformedBehavior(componentContext)
            }.narrow[SerializableCommand])
            .onFailure(SupervisorStrategy.restartWithBackoff(1.second, 5.minute, 0.2)),
          name
        ).withSettings(clusterSingletonSettings(ClusterSingletonSettings(actorSystem))))

        private[components] def fromActorContext(actorContext: ActorContext[Command]): ComponentContextS with ComponentContext.Actor[Command]

        override private[components] val componentCodePositionMaterializer = outerSelf.componentCodePositionMaterializer
      }
    }

    abstract class EventSourced(implicit val componentCodePositionMaterializer: CodePositionMaterializer) extends SingletonT with ComponentT.EventSourcedT {
      outerSelf =>

      trait BaseComponent extends super.SingletonBaseComponentT with super.EventSourcedBaseComponentT {
        self =>

        override private[components] type ComponentContextS = ComponentContext with ComponentContext.EventSourced

        private val persistenceId = PersistenceId.ofUniqueId(name)

        override val tagGenerator = TagGenerator(name, 1)

        def generateTag(context: ComponentContextS) = tagGenerator.generateTag(0)

        override def fromActorContext(_actorContext: ActorContext[Command]) = new ComponentContext with ComponentContext.Actor[Command] with ComponentContext.EventSourced {
          override val persistenceId = self.persistenceId
          override val actorContext = _actorContext
          override protected lazy val loggerClass = generateLoggerClass

          override protected def logContext = super.logContext + outerSelf.logContext
        }

        override private[components] def managedProjections(implicit _actorSystem: ActorSystem[_]) = projections.map(projection => new ManagedProjection[Event, String](projection.name, tagGenerator, identity) {
          override implicit val actorSystem = _actorSystem

          override def handle = projection.handler.compose {
            case (event, _) => event -> new ComponentContext with ComponentContext.EventSourced with ComponentContext.Projection {
              override val persistenceId = self.persistenceId
              override val name = projection.name
              override protected lazy val loggerClass = generateLoggerClass
              override implicit val actorSystem = _actorSystem

              protected override def logContext = super.logContext + outerSelf.logContext
            }
          }
        })
      }
    }

    object EventSourced {
      abstract class WithSnapshots(implicit override val componentCodePositionMaterializer: CodePositionMaterializer) extends EventSourced with ComponentT.EventSourcedT.SnapshotsT {
        outerSelf =>
        trait BaseComponent extends super.BaseComponent with SnapshotsBaseComponentT {
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

      override def fromActorContext(_actorContext: ActorContext[Command]) = new ComponentContext with ComponentContext.Actor[Command] {
        override val actorContext = _actorContext
        override protected lazy val loggerClass = generateLoggerClass

        protected override def logContext = super.logContext + outerSelf.logContext
      }
    }
  }

  trait ShardedComponent[OuterComponentT <: Sharded.ShardedT] extends Component[OuterComponentT] {
    def entityRef(entityId: component.EntityId): EntityRef[component.SerializableCommand]
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

    trait JsonEntityId {
      _: ShardedT =>

      implicit val entityIdCirceCodec: Codec[EntityId]

      implicit val entityIdCodec: EntityIdCodec[EntityId] = EntityIdCodec[EntityId](
        _.asJson.noSpacesSortKeys,
        _.pipe(parser.parse).flatMap(_.as[EntityId]).toTry
      )
    }

    private[components] sealed trait ShardedT extends ComponentT {
      outerSelf =>

      override type Wiring = ShardedComponent[outerSelf.type]

      type EntityId

      implicit val entityIdCodec: EntityIdCodec[EntityId]

      val typeKey: EntityTypeKey[SerializableCommand]

      def generateTypeKey(implicit classTag: ClassTag[SerializableCommand]) = EntityTypeKey[SerializableCommand](name)

      val clusterShardingSettings: ClusterShardingSettings => ClusterShardingSettings = identity

      override type BaseComponent <: ShardedBaseComponentT

      override def init(innerComponent: => BaseComponent)(implicit actorSystem: => ActorSystem[_]) =
        new ShardedComponent[outerSelf.type] {
          override private[components] def delayedInit() = {
            innerComponent.initSharding()(actorSystem)
            super.delayedInit()
          }

          override private[components] val component = outerSelf

          override def entityRef(entityId: EntityId) = innerComponent.entityRef(entityId)(actorSystem)

          override private[components] val serializers = outerSelf.serializers

          override private[components] lazy val managedProjections = innerComponent.managedProjections(actorSystem)
        }

      private[components] trait ShardedBaseComponentT extends super.BaseComponentT {
        self =>

        def entityRef(entityId: EntityId)(implicit actorSystem: ActorSystem[_]) = {
          val clusterSharding: ClusterSharding = ClusterSharding(actorSystem)

          clusterSharding.entityRefFor(typeKey, entityIdCodec.encode(entityId))
        }

        private[components] def initSharding()(implicit actorSystem: ActorSystem[_]) = {
          val clusterSharding: ClusterSharding = ClusterSharding(actorSystem)

          clusterSharding.init(Entity(typeKey)(entityContext =>
            Behaviors.supervise(
              Behaviors.setup[Command](_actorContext => transformedBehavior(fromActorContext(_actorContext, entityIdCodec.decode(entityContext.entityId).get))).narrow[SerializableCommand]
            ).onFailure(SupervisorStrategy.restartWithBackoff(1.second, 5.minute, 0.2)),
          ).withSettings(clusterShardingSettings(ClusterShardingSettings(actorSystem))))
        }

        private[components] def fromActorContext(actorContext: ActorContext[Command], entityId: EntityId): ComponentContextS with ComponentContext.Actor[Command]

        override private[components] val componentCodePositionMaterializer = outerSelf.componentCodePositionMaterializer
      }
    }

    abstract class EventSourced(implicit val componentCodePositionMaterializer: CodePositionMaterializer) extends ShardedT with ComponentT.EventSourcedT {
      outerSelf =>
      trait BaseComponent extends super.ShardedBaseComponentT with super.EventSourcedBaseComponentT {
        self =>

        override type ComponentContextS = ComponentContext with ComponentContext.Sharded[outerSelf.SerializableCommand, outerSelf.EntityId] with ComponentContext.EventSourced

        override val tagGenerator = TagGenerator(name, 1)

        override def generateTag(context: ComponentContextS) = tagGenerator.generateTag(entityIdCodec.encode(context.entityId))

        override def fromActorContext(_actorContext: ActorContext[Command], _entityId: EntityId) = new ComponentContext with ComponentContext.Actor[Command] with ComponentContext.Sharded[SerializableCommand, EntityId] with ComponentContext.EventSourced {
          override val entityId = _entityId
          override val persistenceId = PersistenceId(typeKey.name, outerSelf.entityIdCodec.encode(entityId))
          override val actorContext = _actorContext

          private lazy val clusterSharding: ClusterSharding = ClusterSharding(actorContext.system)

          override def entityRef(entityId: EntityId) = clusterSharding.entityRefFor(typeKey, outerSelf.entityIdCodec.encode(entityId))

          override protected lazy val loggerClass = generateLoggerClass

          protected override def logContext = super.logContext + outerSelf.logContext

          override private[components] val entityIdCodec = outerSelf.entityIdCodec
        }

        override private[components] def managedProjections(implicit _actorSystem: ActorSystem[_]) = {
          lazy val clusterSharding: ClusterSharding = ClusterSharding(_actorSystem)

          projections.map(projection => new ManagedProjection[Event, EntityId](projection.name, tagGenerator, entityIdCodec.decode(_).get) {
            override implicit val actorSystem = _actorSystem

            override def handle = projection.handler.compose {
              case (event, _entityId) => event -> new ComponentContext with ComponentContext.Sharded[SerializableCommand, EntityId] with ComponentContext.Projection {
                override val entityId = _entityId

                override def entityRef(entityId: EntityId) = clusterSharding.entityRefFor(typeKey, outerSelf.entityIdCodec.encode(entityId))

                override private[components] val entityIdCodec = outerSelf.entityIdCodec
                override val name = projection.name
                override val persistenceId = PersistenceId(typeKey.name, outerSelf.entityIdCodec.encode(entityId))
                override protected lazy val loggerClass = generateLoggerClass
                override implicit val actorSystem = _actorSystem

                protected override def logContext = super.logContext + outerSelf.logContext
              }
            }
          })
        }
      }
    }

    object EventSourced {
      abstract class WithSnapshots(implicit override val componentCodePositionMaterializer: CodePositionMaterializer) extends EventSourced with ComponentT.EventSourcedT.SnapshotsT {
        outerSelf =>
        trait BaseComponent extends super.BaseComponent with SnapshotsBaseComponentT {
          override type ComponentContextS = ComponentContext with ComponentContext.Sharded[outerSelf.SerializableCommand, outerSelf.EntityId] with ComponentContext.EventSourced
        }
      }
    }
  }

  abstract class Sharded(implicit val componentCodePositionMaterializer: CodePositionMaterializer) extends Sharded.ShardedT {
    outerSelf =>

    trait BaseComponent extends super.ShardedBaseComponentT {
      override final private[components] type ComponentContextS = ComponentContext with ComponentContext.Sharded[outerSelf.SerializableCommand, outerSelf.EntityId]

      override private[components] type BehaviorS = Behavior[Command]

      override def fromActorContext(_actorContext: ActorContext[Command], _entityId: EntityId) = {
        lazy val clusterSharding: ClusterSharding = ClusterSharding(_actorContext.system)

        new ComponentContext with ComponentContext.Actor[Command] with ComponentContext.Sharded[SerializableCommand, EntityId] {

          override val entityId = _entityId
          override val actorContext = _actorContext

          override def entityRef(entityId: EntityId) = clusterSharding.entityRefFor(typeKey, outerSelf.entityIdCodec.encode(entityId))

          override protected lazy val loggerClass = generateLoggerClass

          override protected def logContext = super.logContext + outerSelf.logContext

          override val entityIdCodec = outerSelf.entityIdCodec
        }
      }
    }
  }
}
