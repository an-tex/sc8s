package net.sc8s.akka.components.testkit

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.cluster.sharding.typed.testkit.scaladsl.TestEntityRef
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.SerializationSettings
import akka.persistence.typed.PersistenceId
import net.sc8s.akka.components.ClusterComponent
import net.sc8s.akka.components.ClusterComponent.Sharded.EntityIdCodec
import net.sc8s.akka.components.ClusterComponent._
import net.sc8s.logstage.elastic.Logging

import scala.reflect.ClassTag

trait ClusterComponentTestKit {
  self: akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit with Logging =>

  def spawnComponent[
    OuterComponentT <: Singleton : ClassTag
  ](
     outerComponent: OuterComponentT
   )(
     innerComponent: outerComponent.BaseComponent
   ): ActorRef[outerComponent.SerializableCommand] = testKit.spawn(
    Behaviors.setup[outerComponent.Command](_actorContext =>
      innerComponent.transformedBehavior(
        new ComponentContext with ComponentContext.Actor[outerComponent.Command] {
          override val actorContext = _actorContext
          override protected lazy val loggerClass = innerComponent.generateLoggerClass
        }
      )
    )
  )

  def spawnComponent[
    OuterComponentT <: Singleton.EventSourced : ClassTag
  ](
     outerComponent: OuterComponentT
   )(
     innerComponent: outerComponent.BaseComponent
   ): EventSourcedBehaviorTestKit[outerComponent.Command, outerComponent.Event, outerComponent.State] =
    EventSourcedBehaviorTestKit(system,
      Behaviors.setup[outerComponent.Command](_actorContext =>
        innerComponent.transformedBehavior(
          new ComponentContext with ComponentContext.Actor[outerComponent.Command] with ComponentContext.EventSourced {
            override val actorContext = _actorContext
            override protected lazy val loggerClass = innerComponent.generateLoggerClass
            override val persistenceId = PersistenceId.ofUniqueId(loggerClass)
          }
        )
      ),
      SerializationSettings.enabled.withVerifyState(outerComponent.isInstanceOf[ComponentT.EventSourcedT.SnapshotsT])
    )

  def spawnComponentWithEntityRefProbes[
    OuterComponentT <: Sharded : ClassTag
  ](
     outerComponent: OuterComponentT,
   )(
     innerComponent: outerComponent.BaseComponent,
     _entityId: outerComponent.EntityId,
     entityRefProbes: outerComponent.EntityId => TestProbe[outerComponent.SerializableCommand]
   )(
     implicit _entityIdCodec: EntityIdCodec[outerComponent.EntityId]
   ): ActorRef[outerComponent.SerializableCommand] = testKit.spawn(
    Behaviors.setup[outerComponent.Command](_actorContext =>
      innerComponent.transformedBehavior(
        new ComponentContext with ComponentContext.Actor[outerComponent.Command] with ComponentContext.Sharded[outerComponent.SerializableCommand, outerComponent.EntityId] {
          override val actorContext = _actorContext
          override protected lazy val loggerClass = innerComponent.generateLoggerClass
          override val entityId = _entityId

          override def entityRef(entityId: outerComponent.EntityId) = TestEntityRef(outerComponent.typeKey, entityIdCodec.encode(entityId), entityRefProbes(entityId).ref)

          override private[components] val entityIdCodec = _entityIdCodec
        }
      )
    )
  )

  def spawnComponent[
    OuterComponentT <: Sharded : ClassTag
  ](
     outerComponent: OuterComponentT,
   )(
     innerComponent: outerComponent.BaseComponent,
     _entityId: outerComponent.EntityId,
   )(
     implicit _entityIdCodec: EntityIdCodec[outerComponent.EntityId]
   ): ActorRef[outerComponent.SerializableCommand] = spawnComponentWithEntityRefProbes(outerComponent)(innerComponent, _entityId, {
    _: outerComponent.EntityId => TestProbe[outerComponent.SerializableCommand]
  })

  def spawnComponentWithEntityRefProbes[
    OuterComponentT <: Sharded.EventSourced : ClassTag
  ](
     outerComponent: OuterComponentT,
   )(
     innerComponent: outerComponent.BaseComponent,
     _entityId: outerComponent.EntityId,
     entityRefProbes: outerComponent.EntityId => TestProbe[outerComponent.SerializableCommand] = {
       _: outerComponent.EntityId => TestProbe[outerComponent.SerializableCommand]
     }
   )(
     implicit _entityIdCodec: EntityIdCodec[outerComponent.EntityId]
   ): EventSourcedBehaviorTestKit[outerComponent.Command, outerComponent.Event, outerComponent.State] =
    EventSourcedBehaviorTestKit(system,

      Behaviors.setup[outerComponent.Command](_actorContext =>
        innerComponent.transformedBehavior(
          new ComponentContext with ComponentContext.Actor[outerComponent.Command] with ComponentContext.Sharded[outerComponent.SerializableCommand, outerComponent.EntityId] with ComponentContext.EventSourced {
            override val actorContext = _actorContext
            override protected lazy val loggerClass = innerComponent.generateLoggerClass

            override val persistenceId = PersistenceId(outerComponent.typeKey.name, _entityIdCodec.encode(_entityId))

            override val entityId = _entityId

            override def entityRef(entityId: outerComponent.EntityId) = TestEntityRef(outerComponent.typeKey, entityIdCodec.encode(entityId), entityRefProbes(entityId).ref)

            override private[components] val entityIdCodec = _entityIdCodec
          }
        )
      ),
      SerializationSettings.enabled.withVerifyState(outerComponent.isInstanceOf[ComponentT.EventSourcedT.SnapshotsT])
    )

  def spawnComponent[
    OuterComponentT <: Sharded.EventSourced : ClassTag
  ](
     outerComponent: OuterComponentT,
   )(
     innerComponent: outerComponent.BaseComponent,
     _entityId: outerComponent.EntityId
   )(
     implicit _entityIdCodec: EntityIdCodec[outerComponent.EntityId]
   ): EventSourcedBehaviorTestKit[outerComponent.Command, outerComponent.Event, outerComponent.State] =
    spawnComponentWithEntityRefProbes(outerComponent)(innerComponent, _entityId, {
      _: outerComponent.EntityId => TestProbe[outerComponent.SerializableCommand]
    })

  def createProbe[
    OuterComponentT <: Singleton.SingletonT : ClassTag
  ](
     outerComponent: OuterComponentT
   ) = {
    val testProbe = TestProbe[outerComponent.SerializableCommand]
    new SingletonComponent[OuterComponentT] {
      override val actorRef: ActorRef[outerComponent.SerializableCommand] = testProbe.ref

      override private[components] lazy val component: outerComponent.type = outerComponent

      override private[components] val serializers = Nil
      override private[components] val managedProjections = Nil
    } -> testProbe
  }

  def createProbe[
    OuterComponentT <: Sharded.ShardedT : ClassTag
  ](
     outerComponent: OuterComponentT
   )(
     entityRefProbes: outerComponent.EntityId => TestProbe[outerComponent.SerializableCommand]
   )(implicit _entityIdCodec: EntityIdCodec[outerComponent.EntityId]): ShardedComponent[OuterComponentT] = {
    new ClusterComponent.ShardedComponent[OuterComponentT] {
      override def entityRef(entityId: outerComponent.EntityId): EntityRef[outerComponent.SerializableCommand] = TestEntityRef(outerComponent.typeKey, _entityIdCodec.encode(entityId), entityRefProbes(entityId).ref)

      override private[components] lazy val component: outerComponent.type = outerComponent

      override private[components] val serializers = Nil
      override private[components] val managedProjections = Nil
    }
  }
}