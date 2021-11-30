package net.sc8s.akka.components.testkit

import akka.actor.testkit.typed.scaladsl.ActorTestKit.ApplicationTestConfig
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.testkit.scaladsl.TestEntityRef
import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.SerializationSettings
import akka.persistence.typed.PersistenceId
import com.typesafe.config.{Config, ConfigFactory}
import net.sc8s.akka.components.ClusterComponent
import net.sc8s.akka.components.ClusterComponent.ComponentContext
import net.sc8s.akka.components.ClusterComponent.Sharded.EntityIdCodec
import net.sc8s.logstage.elastic.Logging

/*
compared to net.sc8s.lagom.circe.testkit.ScalaTestWithActorTestKit this is not verifying any serialization as it's provided at compile time
 */
abstract class ScalaTestWithActorTestKit(
                                          additionalConfig: Config = ConfigFactory.empty()
                                        ) extends akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit(
  additionalConfig
    .withFallback(EventSourcedBehaviorTestKit.config)
    .withFallback(PersistenceTestKitSnapshotPlugin.config)
    .withFallback(ConfigFactory.load())
    .withFallback(ApplicationTestConfig),
)

trait ClusterComponentTestKit {
  self: akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit with Logging =>

  def spawnComponent[
    Command,
    SerializableCommand <: Command
  ](
     component: ClusterComponent.Singleton[Command, SerializableCommand]
   ): ActorRef[Command] = testKit.spawn(
    Behaviors.setup[Command](_actorContext =>
      component.behavior(
        new ComponentContext with ComponentContext.Actor[Command] {
          override val log = self.log
          override val actorContext = _actorContext
        }
      )
    )
  )

  def spawnComponent[
    Command,
    SerializableCommand <: Command,
    Event,
    State
  ](
     component: ClusterComponent.Singleton.EventSourced[Command, SerializableCommand, Event, State]
   ): EventSourcedBehaviorTestKit[Command, Event, State] = {
    EventSourcedBehaviorTestKit[Command, Event, State](system,
      Behaviors.setup[Command](_actorContext =>
        component.behavior(
          new ComponentContext with ComponentContext.Actor[Command] with ComponentContext.EventSourced {
            override val log = self.log
            override val actorContext = _actorContext
            override val persistenceId = PersistenceId.ofUniqueId(component.name)
          }
        )
      ),
      // no need as serializers must be provided upon component creation anyway
      SerializationSettings.disabled
    )
  }

  def spawnComponent[
    Command,
    SerializableCommand <: Command,
    EntityId
  ](
     component: ClusterComponent.Sharded[Command, SerializableCommand, EntityId],
     _entityId: EntityId,
     entityRefProbes: EntityId => TestProbe[SerializableCommand] = {
       _: EntityId => TestProbe[SerializableCommand]
     }
   )(
     implicit _entityIdCodec: EntityIdCodec[EntityId]
   ): ActorRef[Command] = testKit.spawn(
    Behaviors.setup[Command](_actorContext =>
      component.behavior(
        new ComponentContext with ComponentContext.Actor[Command] with ComponentContext.Sharded[SerializableCommand, EntityId] {
          override val log = self.log

          override val actorContext = _actorContext

          override val entityId = _entityId

          override def entityRef(entityId: EntityId) = TestEntityRef(component.typeKey, entityIdCodec.encode(entityId), entityRefProbes(entityId).ref)

          override private[components] val entityIdCodec = _entityIdCodec
        }
      )
    )
  )

  def spawnComponent[
    Command,
    SerializableCommand <: Command,
    Event,
    State,
    EntityId
  ](
     component: ClusterComponent.Sharded.EventSourced[Command, SerializableCommand, Event, State, EntityId],
     _entityId: EntityId,
   )(
     implicit _entityIdCodec: EntityIdCodec[EntityId]
   ): EventSourcedBehaviorTestKit[Command, Event, State] = spawnComponent(component, _entityId, {
    _: EntityId => TestProbe[SerializableCommand]
  })

  def spawnComponent[
    Command,
    SerializableCommand <: Command,
    Event,
    State,
    EntityId
  ](
     component: ClusterComponent.Sharded.EventSourced[Command, SerializableCommand, Event, State, EntityId],
     _entityId: EntityId,
     entityRefProbes: EntityId => TestProbe[SerializableCommand]
   )(
     implicit _entityIdCodec: EntityIdCodec[EntityId]
   ): EventSourcedBehaviorTestKit[Command, Event, State] = {
    EventSourcedBehaviorTestKit[Command, Event, State](system,
      Behaviors.setup[Command](_actorContext =>
        component.behavior(
          new ComponentContext with ComponentContext.Actor[Command] with ComponentContext.Sharded[SerializableCommand, EntityId] with ComponentContext.EventSourced {
            override val log = self.log

            override val actorContext = _actorContext

            override val entityId = _entityId

            override def entityRef(entityId: EntityId) = TestEntityRef(component.typeKey, entityIdCodec.encode(entityId), entityRefProbes(entityId).ref)

            override private[components] val entityIdCodec = _entityIdCodec

            override val persistenceId = PersistenceId(component.typeKey.name, entityIdCodec.encode(_entityId))
          }
        )
      ),
      // no need as serializers must be provided upon component creation anyway
      SerializationSettings.disabled
    )
  }
}