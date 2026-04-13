package net.sc8s.akka.components

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.stream.SharedKillSwitch
import izumi.fundamentals.platform.language.CodePositionMaterializer
import izumi.logstage.api.IzLogger
import net.sc8s.logstage.elastic.Logging.IzLoggerTags

import scala.reflect.ClassTag

trait StartStopHandler {
  _: ClusterComponent.ComponentT#BaseComponentT =>

  /*
  * This handler can be added to the "running" behavior of a component to handle the Start and Stop commands.
  * If a command returns another behavior within the "running" semantics, this handler needs to be added there too.
   */
  def addStartStopHandler[
    StartCommand <: CommandT : ClassTag,
    StopCommand <: CommandT {
      val replyTo: ActorRef[Done]
    } : ClassTag
  ](
     idle: Behavior[CommandT],
     killSwitch: SharedKillSwitch,
     handler: PartialFunction[CommandT, Behavior[CommandT]]
   )(
     implicit log: IzLogger,
     pos: CodePositionMaterializer,
   ) = Behaviors.receiveMessage[CommandT](
    handler.orElse({
      case _: StartCommand =>
        log.errorT("alreadyRunning")
        Behaviors.same

      case stop: StopCommand =>
        log.infoT("stopping")
        killSwitch.shutdown()
        stop.replyTo ! Done
        idle
    }: PartialFunction[CommandT, Behavior[CommandT]]))
}
