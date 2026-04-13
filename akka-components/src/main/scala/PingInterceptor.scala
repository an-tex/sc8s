package net.sc8s.akka.components

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, BehaviorInterceptor, TypedActorContext}

import scala.language.reflectiveCalls
import scala.reflect.ClassTag

// don't use this for EventSourcedBehaviors as it would return a ping even if the recovery fails. As the RecoveryCompleted signal can't be intercepted, there's no generic way to do this.
trait PingInterceptor {
  _: ClusterComponent.ComponentT#BaseComponentT =>

  /*
  * Add once to the initial behavior of a component to intercept Ping commands and reply with Done.
   */
  def addPingInterceptor[
    PingCommand <: CommandT {
      val replyTo: ActorRef[Done]
    } : ClassTag,
    CommandT: ClassTag,
  ](behavior: Behavior[CommandT]) =
    Behaviors.intercept(() => new BehaviorInterceptor[CommandT, CommandT]() {
      override def aroundReceive(ctx: TypedActorContext[CommandT], msg: CommandT, target: BehaviorInterceptor.ReceiveTarget[CommandT]) = msg match {
        case ping: PingCommand =>
          ping.replyTo ! Done
          Behaviors.same
        case command => target(ctx, command)
      }
    })(behavior)
}
