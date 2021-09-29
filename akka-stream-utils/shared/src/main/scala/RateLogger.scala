package net.sc8s.akka.stream

import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{ActorAttributes, Supervision}
import izumi.fundamentals.platform.language.CodePositionMaterializer
import izumi.logstage.api.{IzLogger, Log}

import scala.concurrent.duration.{FiniteDuration, _}
import scala.math.BigDecimal.RoundingMode

object RateLogger {

  private case class Stats(allCount: Long = 0, allStart: Long = System.currentTimeMillis(), periodCount: Long = 0, periodStart: Long = System.currentTimeMillis())

  private case object Tick

  type Stage = Long
  type Overall = Long

  def apply[T](
                name: String,
                tick: FiniteDuration = 1.second,
                total: Option[(Stage, Overall)] = None,
                skipNoopLogs: Boolean = true
              )(implicit log: IzLogger, pos: CodePositionMaterializer): Flow[T, T, _] = {

    val ticker = Source.tick(tick, tick, Left(Tick))

    val rateLogger = Sink.fold[Stats, Either[Tick.type, T]](Stats()) {
      case (Stats(allCount, allStart, periodCount, periodStart), Left(_)) =>
        val periodDuration = System.currentTimeMillis() - periodStart
        val allDuration = System.currentTimeMillis() - allStart

        val rate1s = (BigDecimal(periodCount) / (BigDecimal(periodDuration.nonZero) / 1000)).setScale(2, RoundingMode.HALF_UP)
        val rateAll = (BigDecimal(allCount) / (BigDecimal(allDuration.nonZero) / 1000)).setScale(2, RoundingMode.HALF_UP)

        val message = Log.Message(s"${"stats" -> "tag"} $name $rate1s/s $rateAll/s ${allCount -> "stageDone"}") +
          total.fold(Log.Message.empty) { case (stage, overall) =>
            val overallDone = overall - stage + allCount
            val overallDonePercent = ((BigDecimal(overallDone) / overall.nonZero) * 100).setScale(2, RoundingMode.HALF_UP)
            val stageDonePercent = ((BigDecimal(allCount) / stage.nonZero) * 100).setScale(2, RoundingMode.HALF_UP)
            val stageLeft = stage - allCount
            val estimatedSecondsLeft = (stageLeft / rateAll.nonZero).setScale(0, RoundingMode.HALF_UP)
            Log.Message(s" ${stage -> "stageTotal"} $stageLeft $stageDonePercent% $overallDone $overallDonePercent% $estimatedSecondsLeft")
          }
        if (!skipNoopLogs || periodCount > 0) log.log(Log.Level.Info)(message)
        Stats(allCount, allStart)

      case (stats, Right(_)) =>
        stats.copy(periodCount = stats.periodCount + 1, allCount = stats.allCount + 1)
    }

    Flow[T]
      .wireTap(
        Flow[T]
          .map(Right(_))
          .merge(ticker, eagerComplete = true)
          .to(rateLogger).withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider))
      )
  }

  implicit class IntNonZero(x: Int) {
    def nonZero = if (x == 0) 1 else x
  }
  implicit class LongNonZero(x: Long) {
    def nonZero = if (x == 0) 1L else x
  }
  implicit class BigDecimalNonZero(x: BigDecimal) {
    def nonZero = if (x == 0) BigDecimal(1) else x
  }
}
