package net.sc8s.akka.components.testkit

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityRef, EntityTypeKey}
import akka.cluster.sharding.typed.testkit.scaladsl.TestEntityRef
import akka.persistence.query.Offset
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.SerializationSettings
import akka.persistence.typed.PersistenceId
import akka.projection.ProjectionId
import akka.projection.eventsourced.EventEnvelope
import akka.projection.testkit.scaladsl.{ProjectionTestKit, TestProjection, TestSourceProvider}
import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import net.sc8s.akka.components.ClusterComponent
import net.sc8s.akka.components.ClusterComponent.Sharded.EntityIdCodec
import net.sc8s.akka.components.ClusterComponent._
import net.sc8s.logstage.elastic.Logging

import scala.concurrent.Future
import scala.reflect.ClassTag

trait ClusterComponentTestKit {
  self: akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit with Logging =>

  def spawnComponent[
    OuterComponentT <: Singleton
  ](
     outerComponent: OuterComponentT
   )(
     innerComponent: outerComponent.BaseComponent
   ): ActorRef[outerComponent.SerializableCommand] = testKit.spawn(
    Behaviors.setup[outerComponent.Command](_actorContext =>
      innerComponent.transformedBehavior(
        new ComponentContext with ComponentContext.Actor[outerComponent.Command] {
          override val actorContext = _actorContext
          override protected lazy val loggerClass = innerComponent.generateLoggerClass(outerComponent.getClass)
        }
      )
    )
  )

  def spawnComponent[
    OuterComponentT <: Singleton.EventSourced
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
            override protected lazy val loggerClass = innerComponent.generateLoggerClass(outerComponent.getClass)
            override val persistenceId = PersistenceId.ofUniqueId(loggerClass)
          }
        )
      ),
      SerializationSettings.enabled.withVerifyState(outerComponent.isInstanceOf[ComponentT.EventSourcedT.SnapshotsT])
    )

  def spawnComponentWithEntityRefProbes[
    OuterComponentT <: Sharded
  ](
     outerComponent: OuterComponentT,
   )(
     innerComponent: outerComponent.BaseComponent,
     _entityId: outerComponent.EntityId,
     entityRefProbes: outerComponent.EntityId => TestProbe[outerComponent.SerializableCommand]
   ): ActorRef[outerComponent.SerializableCommand] = testKit.spawn(
    Behaviors.setup[outerComponent.Command](_actorContext =>
      innerComponent.transformedBehavior(
        new ComponentContext with ComponentContext.Actor[outerComponent.Command] with ComponentContext.Sharded[outerComponent.SerializableCommand, outerComponent.EntityId] with ComponentContext.ShardedEntity[outerComponent.SerializableCommand] {
          override val actorContext = _actorContext
          override protected lazy val loggerClass = innerComponent.generateLoggerClass(outerComponent.getClass)
          override val entityId = _entityId

          override def entityRefFor(entityId: outerComponent.EntityId) = TestEntityRef(innerComponent.typeKey, outerComponent.entityIdCodec.encode(entityId), entityRefProbes(entityId).ref)

          override private[components] val entityIdCodec = outerComponent.entityIdCodec

          override val entityContext = new EntityContext[outerComponent.SerializableCommand](innerComponent.typeKey, _entityId.toString, testKit.system.deadLetters)

          override private[components] val typeKey = innerComponent.typeKey
        }
      )
    )
  )

  def spawnComponent[
    OuterComponentT <: Sharded
  ](
     outerComponent: OuterComponentT,
   )(
     innerComponent: outerComponent.BaseComponent,
     _entityId: outerComponent.EntityId,
   ): ActorRef[outerComponent.SerializableCommand] = spawnComponentWithEntityRefProbes(outerComponent)(innerComponent, _entityId, {
    _: outerComponent.EntityId => TestProbe[outerComponent.SerializableCommand]
  })

  def spawnComponentWithEntityRefProbes[
    OuterComponentT <: Sharded.EventSourced
  ](
     outerComponent: OuterComponentT,
   )(
     innerComponent: outerComponent.BaseComponent,
     _entityId: outerComponent.EntityId,
     entityRefProbes: outerComponent.EntityId => TestProbe[outerComponent.SerializableCommand]
   ): EventSourcedBehaviorTestKit[outerComponent.Command, outerComponent.Event, outerComponent.State] =
    EventSourcedBehaviorTestKit(system,

      Behaviors.setup[outerComponent.Command](_actorContext =>
        innerComponent.transformedBehavior(
          new ComponentContext with ComponentContext.Actor[outerComponent.Command] with ComponentContext.Sharded[outerComponent.SerializableCommand, outerComponent.EntityId] with ComponentContext.EventSourced with ComponentContext.ShardedEntity[outerComponent.SerializableCommand] {
            override val actorContext = _actorContext
            override protected lazy val loggerClass = innerComponent.generateLoggerClass(outerComponent.getClass)

            override val persistenceId = PersistenceId(innerComponent.typeKey.name, outerComponent.entityIdCodec.encode(_entityId))

            override val entityId = _entityId

            override def entityRefFor(entityId: outerComponent.EntityId) = TestEntityRef(innerComponent.typeKey, outerComponent.entityIdCodec.encode(entityId), entityRefProbes(entityId).ref)

            override private[components] val entityIdCodec = outerComponent.entityIdCodec

            override val entityContext = new EntityContext[outerComponent.SerializableCommand](innerComponent.typeKey, _entityId.toString, testKit.system.deadLetters)

            override private[components] val typeKey = innerComponent.typeKey
          }
        )
      ),
      SerializationSettings.enabled.withVerifyState(outerComponent.isInstanceOf[ComponentT.EventSourcedT.SnapshotsT])
    )

  def spawnComponent[
    OuterComponentT <: Sharded.EventSourced
  ](
     outerComponent: OuterComponentT,
   )(
     innerComponent: outerComponent.BaseComponent,
     _entityId: outerComponent.EntityId
   ): EventSourcedBehaviorTestKit[outerComponent.Command, outerComponent.Event, outerComponent.State] =
    spawnComponentWithEntityRefProbes(outerComponent)(innerComponent, _entityId, {
      _: outerComponent.EntityId => TestProbe[outerComponent.SerializableCommand]
    })

  def createProbe[
    OuterComponentT <: Singleton.SingletonT
  ](
     outerComponent: OuterComponentT
   ) = {
    val testProbe = TestProbe[outerComponent.SerializableCommand]
    new SingletonComponent[OuterComponentT] {
      override val actorRef: ActorRef[outerComponent.SerializableCommand] = testProbe.ref

      override private[components] lazy val innerComponent = ???
      override private[components] lazy val component: outerComponent.type = outerComponent

      override private[components] val serializers = Nil
      override private[components] val managedProjections = Nil
    } -> testProbe
  }

  def createProbe[
    OuterComponentT <: Sharded.ShardedT
  ](
     outerComponent: OuterComponentT
   )(
     entityRefProbes: outerComponent.EntityId => TestProbe[outerComponent.SerializableCommand]
   )(
     implicit classTag: ClassTag[outerComponent.SerializableCommand]
   ): ShardedComponent[OuterComponentT] = {
    new ClusterComponent.ShardedComponent[OuterComponentT] {
      override def entityRefFor(entityId: outerComponent.EntityId): EntityRef[outerComponent.SerializableCommand] = TestEntityRef(
        EntityTypeKey[outerComponent.SerializableCommand]("any"), outerComponent.entityIdCodec.encode(entityId), entityRefProbes(entityId).ref)

      override private[components] lazy val innerComponent = ???
      override private[components] lazy val component: outerComponent.type = outerComponent

      override private[components] val serializers = Nil
      override private[components] val managedProjections = Nil
    }
  }

  lazy val projectionTestKit = ProjectionTestKit(system)

  def testProjection[
    OuterComponentT <: Sharded.EventSourced,
  ](
     outerComponent: OuterComponentT
   )
   (
     innerComponent: outerComponent.BaseComponent,
   )(
     projection: Projection[outerComponent.Event, innerComponent.ComponentContextS with ComponentContext.Projection], events: Source[(outerComponent.EntityId, outerComponent.Event), NotUsed],
     entityRefProbes: outerComponent.EntityId => TestProbe[outerComponent.SerializableCommand] = {
       _: outerComponent.EntityId => TestProbe[outerComponent.SerializableCommand]
     }
   ) = {
    TestProjection(ProjectionId(projection.name, "tag0"), TestSourceProvider[Offset, EventEnvelope[outerComponent.Event]](
      events.zipWithIndex.map { case ((entityId, event), index) =>
        EventEnvelope(Offset.noOffset, PersistenceId(innerComponent.typeKey.name, outerComponent.entityIdCodec.encode(entityId)).id, index, event, 0)
      },
      _.offset,
    ), () => (envelope: EventEnvelope[outerComponent.Event]) => {
      val projectionContext = new ComponentContext with ComponentContext.Sharded[outerComponent.SerializableCommand, outerComponent.EntityId] with ComponentContext.EventSourced with ComponentContext.Projection {
        override val name = projection.name
        override implicit val actorSystem = self.system
        override val persistenceId = PersistenceId.ofUniqueId(envelope.persistenceId)
        override val entityId = outerComponent.entityIdCodec.decode(persistenceId.entityId).get

        override def entityRefFor(entityId: outerComponent.EntityId) = TestEntityRef(innerComponent.typeKey, outerComponent.entityIdCodec.encode(entityId), entityRefProbes(entityId).ref)

        override val entityIdCodec: EntityIdCodec[outerComponent.EntityId] = outerComponent.entityIdCodec

        override private[components] val typeKey = innerComponent.typeKey
      }
      projection.handler.lift(envelope.event -> projectionContext).getOrElse(Future.successful(Done))
    })
  }

  def testProjection[
    OuterComponentT <: Singleton.EventSourced,
  ](
     outerComponent: OuterComponentT
   )
   (
     innerComponent: outerComponent.BaseComponent,
   )(
     projection: Projection[outerComponent.Event, innerComponent.ComponentContextS with ComponentContext.Projection], events: Source[outerComponent.Event, NotUsed],
   ) = {
    TestProjection(ProjectionId(projection.name, "tag0"), TestSourceProvider[Offset, EventEnvelope[outerComponent.Event]](
      events.zipWithIndex.map { case (event, index) =>
        EventEnvelope(Offset.noOffset, innerComponent.persistenceId.id, index, event, 0)
      },
      _.offset,
    ), () => (envelope: EventEnvelope[outerComponent.Event]) => {
      val projectionContext = new ComponentContext with ComponentContext.EventSourced with ComponentContext.Projection {
        override val name = projection.name
        override implicit val actorSystem = self.system
        override val persistenceId = PersistenceId.ofUniqueId(envelope.persistenceId)
      }
      projection.handler.lift(envelope.event -> projectionContext).getOrElse(Future.successful(Done))
    })
  }
}