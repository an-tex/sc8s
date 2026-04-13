package net.sc8s.akka.components.persistence.r2dbc.common

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.pattern.StatusReply
import akka.persistence.query.PersistenceQuery
import akka.persistence.r2dbc.cleanup.scaladsl.EventSourcedCleanup
import akka.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import akka.stream.KillSwitches
import akka.stream.scaladsl.{Merge, Source}
import com.typesafe.akka.extension.quartz.QuartzSchedulerTypedExtension
import io.circe.Codec
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import net.sc8s.akka.circe.CirceSerializer
import net.sc8s.akka.circe.implicits._
import net.sc8s.akka.components.{ClusterComponent, PingInterceptor, StartStopHandler}
import net.sc8s.logstage.elastic.Logging.IzLoggerTags

import java.time.{Duration, Instant}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success}

trait EventsCleanup {
  _: ClusterComponent.ComponentT.EventSourcedT#EventSourcedBaseComponentT =>

  val cleanupEventsBeforeAgo: FiniteDuration
}

/*
* Either wire in this directly or use ClusterComponentsR2dbcPersistentManagement
 */
object EntityCleanupActor extends ClusterComponent.Singleton {

  private def createKillSwitch = KillSwitches.shared("entity-cleanup")

  private var killSwitch = createKillSwitch

  class Component(clusterComponents: Set[ClusterComponent.Component[_]]) extends BaseComponent with PingInterceptor with StartStopHandler {
    override val name = "entity-cleanup"

    override val behavior = { context =>
      import context.actorContext.executionContext
      import context.{actorContext, log, materializer}

      val quartz = QuartzSchedulerTypedExtension(actorContext.system)
      quartz.cancelJob("entity-cleanup")
      quartz.scheduleTyped(
        "entity-cleanup",
        actorContext.self,
        Command.Start(None, actorContext.system.ignoreRef[StatusReply[Done]]),
      )

      val cleanup = new EventSourcedCleanup(actorContext.system.classicSystem)

      val entityTypesToCleanEventsAgo = clusterComponents.map(_.innerComponent).collect {
        case component: EventsCleanup => component.name -> component.cleanupEventsBeforeAgo
      }.toMap

      log.infoT("initialized", s"$entityTypesToCleanEventsAgo")

      def startCleanup(onlyEntity: Option[String], replyTo: ActorRef[StatusReply[Done]]) = {
        val startedAt = Instant.now()

        val filteredEntityTypesToCleanEventsAgo =
          onlyEntity.fold(
            entityTypesToCleanEventsAgo
          )(
            onlyEntity => entityTypesToCleanEventsAgo.view.filterKeys(_ == onlyEntity).toMap
          )

        log.infoT("startingCleanup", s"${filteredEntityTypesToCleanEventsAgo -> "entities"}")

        killSwitch = createKillSwitch

        val persistenceIdsSources = filteredEntityTypesToCleanEventsAgo.toSeq.map { case (entityType, cleanupDuration) =>
          val cleanupUntil = startedAt.minus(java.time.Duration.ofMillis(cleanupDuration.toMillis))
          PersistenceQuery(actorContext.system.classicSystem)
            .readJournalFor[R2dbcReadJournal](R2dbcReadJournal.Identifier)
            .currentPersistenceIds(entityType, None, Long.MaxValue)
            .map(persistenceId => (persistenceId, cleanupUntil))
        }

        actorContext.pipeToSelf {
          Source
            .combine(persistenceIdsSources)(Merge(_))
            .idleTimeout(30.minutes)
            .via(killSwitch.flow)
            .mapAsyncUnordered(32) { case (persistenceId, cleanupUntil) =>
              cleanup.deleteEventsBefore(persistenceId, cleanupUntil).map { _ =>
                log.debugT("deletedEventsBefore", s"$persistenceId $cleanupUntil")
                1
              }
            }
            .runFold(0)(_ + _)
        } {
          case Success(count) => Command.Finished(count)
          case Failure(throwable) => Command.Failed(throwable)
        }

        replyTo ! StatusReply.Success(Done)
        running(startedAt)
      }

      def idle: Behaviors.Receive[Command] =
        Behaviors.receiveMessage[Command] {
          case Command.Start(onlyEntity, replyTo) =>
            startCleanup(onlyEntity, replyTo)

          case Command.Stop(replyTo) =>
            log.errorT("notRunning")
            replyTo ! Done
            Behaviors.same

          // might arrive after stopping
          case Command.Failed(_) => Behaviors.same
          case Command.Finished(_) => Behaviors.same
        }

      def running(startedAt: Instant) = {
        lazy val (duration, durationSeconds) = Duration.between(startedAt, Instant.now()).readableAndSeconds

        setupRunningBehavior {
          case Command.Finished(persistenceIdsCleaned) =>
            log.infoT("cleanupFinished", s"$persistenceIdsCleaned $duration $durationSeconds")
            idle

          case Command.Failed(throwable) =>
            log.errorT("cleanupFailed", s"due to $throwable $duration $durationSeconds")
            idle
        }
      }

      def setupRunningBehavior(handler: PartialFunction[Command, Behavior[Command]]) =
        addStartStopHandler[Command.Start, Command.Stop](idle, killSwitch, handler)

      addPingInterceptor[Command.Ping, Command](idle)
    }
  }

  implicit class DurationOps(duration: Duration) {
    def toHhMMssString: String =
      "%02d:%02d:%02d".format(duration.toHours, duration.toMinutesPart, duration.toSecondsPart)

    def readableAndSeconds: (String, Long) =
      (toHhMMssString, duration.toSeconds)
  }

  sealed trait Command

  sealed trait SerializableCommand extends Command

  object Command {
    case class Start(onlyEntity: Option[String], replyTo: ActorRef[StatusReply[Done]]) extends SerializableCommand

    case class Stop(replyTo: ActorRef[Done]) extends SerializableCommand

    case class Ping(replyTo: ActorRef[Done]) extends SerializableCommand

    private[EntityCleanupActor] case class Finished(count: Int) extends Command

    private[EntityCleanupActor] case class Failed(throwable: Throwable) extends Command

    implicit val codec: Codec[SerializableCommand] = deriveConfiguredCodec
  }

  override val commandSerializer = CirceSerializer()
}
