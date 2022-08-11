package net.sc8s.akka.persistence.utils

import akka.actor.typed.Signal
import akka.persistence.typed._
import izumi.fundamentals.platform.language.CodePositionMaterializer
import izumi.logstage.api.{IzLogger, Log}

trait SignalHandlers {
  def defaultSignalHandler[State](implicit log: IzLogger, pos: CodePositionMaterializer): PartialFunction[(State, Signal), Unit] = ({
    case (_, RecoveryCompleted) =>
      log.log(Log.Level.Debug)(s"${"recoveryCompleted" -> "tag"}")

    case (_, SnapshotCompleted(snapshotMetadata)) =>
      log.log(Log.Level.Debug)(s"${"snapshotCompleted" -> "tag"} with ${snapshotMetadata -> null}")

    case (_, DeleteSnapshotsCompleted(deletionTarget)) =>
      log.log(Log.Level.Debug)(s"${"snapshotDeleteCompleted" -> "tag"} with $deletionTarget")

    case (_, DeleteEventsCompleted(toSequenceNr)) =>
      log.log(Log.Level.Debug)(s"${"deleteEventsCompleted" -> "tag"} up $toSequenceNr")
  }: PartialFunction[(State, Signal), Unit]).orElse(errorSignalHandler)

  def errorSignalHandler[State](implicit log: IzLogger, pos: CodePositionMaterializer): PartialFunction[(State, Signal), Unit] = {
    case (_, SnapshotFailed(snapshotMetadata, failure)) =>
      log.log(Log.Level.Error)(s"${"snapshotFailed" -> "tag"} with ${snapshotMetadata -> null} due to $failure")

    case (_, DeleteSnapshotsFailed(deletionTarget, failure)) =>
      log.log(Log.Level.Warn)(s"${"snapshotDeleteFailed" -> "tag"} with $deletionTarget due to $failure")

    case (_, DeleteEventsFailed(toSequenceNr, failure)) =>
      log.log(Log.Level.Warn)(s"${"deleteEventsFailed" -> "tag"} up $toSequenceNr due to $failure")
  }

  def withDefaultSignalHandler[State](overridingSignalHandler: PartialFunction[(State, Signal), Unit])(implicit log: IzLogger, pos: CodePositionMaterializer) = overridingSignalHandler.orElse(defaultSignalHandler)
}