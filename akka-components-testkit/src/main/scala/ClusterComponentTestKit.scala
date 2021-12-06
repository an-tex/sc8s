package net.sc8s.akka.components.testkit

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.testkit.scaladsl.TestEntityRef
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.SerializationSettings
import akka.persistence.typed.PersistenceId
import net.sc8s.akka.components.ClusterComponent.Sharded.EventSourced.InnerShardedEventSourcedComponentContext
import net.sc8s.akka.components.ClusterComponent.Sharded.{EntityIdCodec, InnerShardedComponent, InnerShardedComponentContext}
import net.sc8s.akka.components.ClusterComponent.Singleton.EventSourced.InnerSingletonEventSourcedComponentContext
import net.sc8s.akka.components.ClusterComponent.Singleton.{InnerSingletonComponent, InnerSingletonComponentContext}
import net.sc8s.akka.components.ClusterComponent.{ComponentContext, InnerComponent}
import net.sc8s.logstage.elastic.Logging

import scala.reflect.ClassTag

trait ClusterComponentTestKit {
  self: akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit with Logging =>

  def spawnComponent[
    InnerComponentT <: InnerSingletonComponent[InnerComponentT] with InnerSingletonComponentContext[InnerComponentT] : ClassTag
  ](
     component: InnerSingletonComponent[InnerComponentT] with InnerSingletonComponentContext[InnerComponentT]
   ): ActorRef[component.Command] = testKit.spawn(
    Behaviors.setup[component.Command](_actorContext =>
      component.behavior(
        new ComponentContext with ComponentContext.Actor[component.Command] {
          override val actorContext = _actorContext
          override protected lazy val loggerClass = component.generateLoggerClass
        }
      )
    )
  )

  def spawnComponent[
    InnerComponentT <: InnerSingletonComponent[InnerComponentT] with InnerComponent.EventSourced[InnerComponentT] with InnerSingletonEventSourcedComponentContext[InnerComponentT] : ClassTag
  ](
     component: InnerSingletonComponent[InnerComponentT] with InnerComponent.EventSourced[InnerComponentT] with InnerSingletonEventSourcedComponentContext[InnerComponentT]
   ): EventSourcedBehaviorTestKit[component.Command, component.Event, component.State] = {
    EventSourcedBehaviorTestKit[component.Command, component.Event, component.State](system,
      Behaviors.setup[component.Command](_actorContext =>
        component.behavior(
          new ComponentContext with ComponentContext.Actor[component.Command] with ComponentContext.EventSourced {
            override val actorContext = _actorContext
            override protected lazy val loggerClass = component.generateLoggerClass
            override val persistenceId = PersistenceId.ofUniqueId(loggerClass)
          }
        )
      ),
      SerializationSettings.enabled.withVerifyState(component.isInstanceOf[InnerComponent.EventSourced.WithSnapshots[InnerComponentT]])
    )
  }

  def spawnComponent[
    InnerComponentT <: InnerShardedComponent[InnerComponentT] with InnerShardedComponentContext[InnerComponentT] : ClassTag
  ](
     component: InnerShardedComponent[InnerComponentT] with InnerShardedComponentContext[InnerComponentT]
   )(
     entityId: component.EntityId
   )(implicit _entityIdCodec: EntityIdCodec[component.EntityId]): ActorRef[component.Command] = spawnComponentWithEntityRefProbes(component)(entityId, {
    _: component.EntityId => TestProbe[component.SerializableCommand]
  })

  def spawnComponentWithEntityRefProbes[
    InnerComponentT <: InnerShardedComponent[InnerComponentT] with InnerShardedComponentContext[InnerComponentT] : ClassTag
  ](
     component: InnerShardedComponent[InnerComponentT] with InnerShardedComponentContext[InnerComponentT]
   )(
     _entityId: component.EntityId,
     entityRefProbes: component.EntityId => TestProbe[component.SerializableCommand]
   )(implicit _entityIdCodec: EntityIdCodec[component.EntityId]): ActorRef[component.Command] = testKit.spawn(
    Behaviors.setup[component.Command](_actorContext =>
      component.behavior(
        new ComponentContext with ComponentContext.Actor[component.Command] with ComponentContext.Sharded[component.SerializableCommand, component.EntityId] {
          override val actorContext = _actorContext
          override protected lazy val loggerClass = component.generateLoggerClass
          override val entityId = _entityId

          override def entityRef(entityId: component.EntityId) = TestEntityRef(component.typeKey, entityIdCodec.encode(entityId), entityRefProbes(entityId).ref)

          override private[components] val entityIdCodec = _entityIdCodec
        }
      )
    )
  )

  def spawnComponent[
    InnerComponentT <: InnerShardedComponent[InnerComponentT] with InnerComponent.EventSourced[InnerComponentT] with InnerShardedEventSourcedComponentContext[InnerComponentT] : ClassTag
  ](
     component: InnerShardedComponent[InnerComponentT] with InnerComponent.EventSourced[InnerComponentT] with InnerShardedEventSourcedComponentContext[InnerComponentT]
   )(
     entityId: component.EntityId
   )(implicit _entityIdCodec: EntityIdCodec[component.EntityId]): EventSourcedBehaviorTestKit[component.Command, component.Event, component.State] =
    spawnComponentWithEntityRefProbes(component)(entityId, {
      _: component.EntityId => TestProbe[component.SerializableCommand]
    })

  def spawnComponentWithEntityRefProbes[
    InnerComponentT <: InnerShardedComponent[InnerComponentT] with InnerComponent.EventSourced[InnerComponentT] with InnerShardedEventSourcedComponentContext[InnerComponentT] : ClassTag
  ](
     component: InnerShardedComponent[InnerComponentT] with InnerComponent.EventSourced[InnerComponentT] with InnerShardedEventSourcedComponentContext[InnerComponentT]
   )(
     _entityId: component.EntityId,
     entityRefProbes: component.EntityId => TestProbe[component.SerializableCommand]
   )(implicit _entityIdCodec: EntityIdCodec[component.EntityId]): EventSourcedBehaviorTestKit[component.Command, component.Event, component.State] =
    EventSourcedBehaviorTestKit[component.Command, component.Event, component.State](system,
      Behaviors.setup[component.Command](_actorContext =>
        component.behavior(
          new ComponentContext with ComponentContext.Actor[component.Command] with ComponentContext.Sharded[component.SerializableCommand, component.EntityId] with ComponentContext.EventSourced {
            override val actorContext = _actorContext
            override protected lazy val loggerClass = component.generateLoggerClass
            override val entityId = _entityId

            override def entityRef(entityId: component.EntityId) = TestEntityRef(component.typeKey, entityIdCodec.encode(entityId), entityRefProbes(entityId).ref)

            override private[components] val entityIdCodec = _entityIdCodec

            override val persistenceId = PersistenceId(component.typeKey.name, entityIdCodec.encode(_entityId))
          }
        )
      ),
      SerializationSettings.enabled.withVerifyState(component.isInstanceOf[InnerComponent.EventSourced.WithSnapshots[InnerComponentT]])
    )
}