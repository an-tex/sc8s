package net.sc8s.akka.stream

import akka.NotUsed
import akka.stream.scaladsl.{Keep, RestartSource, Sink, Source}
import akka.stream.{Materializer, RestartSettings}
import izumi.fundamentals.platform.language.CodePositionMaterializer
import izumi.logstage.api.{IzLogger, Log}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

object RetryUtils {
  val defaultRestartSettings = RestartSettings(10.seconds, 5.minutes, 0.2).withMaxRestarts(5, 1.hour)

  def retryWithBackoff[T, M](
                              source: () => Source[T, M],
                              message: Throwable => Log.Message = exception => s"$exception - retrying...",
                              restartSettings: RestartSettings = defaultRestartSettings
                            )(
                              implicit mat: Materializer,
                              ec: ExecutionContext,
                              log: IzLogger,
                              pos: CodePositionMaterializer
                            ): Source[T, NotUsed] =
    RestartSource.onFailuresWithBackoff(restartSettings) { () =>
      val (eventualDone, src) = source().watchTermination()(Keep.right).preMaterialize()
      eventualDone.onComplete {
        case Failure(exception) => log
          // additional context for filtering
          .withCustomContext("retryWithBackoff" -> true)
          .log(Log.Level.Warn)(message(exception))
        case _ =>
      }
      src
    }

  def retryWithBackoffF[T](
                            future: () => Future[T],
                            message: Throwable => Log.Message = exception => s"$exception - retrying...",
                            restartSettings: RestartSettings = defaultRestartSettings
                          )(
                            implicit mat: Materializer,
                            ec: ExecutionContext,
                            log: IzLogger,
                            pos: CodePositionMaterializer
                          ): Source[T, NotUsed] =
    retryWithBackoff(() => Source.future(future()), message, restartSettings)

  def retryWithBackoffFuture[Out](
                                   future: () => Future[Out],
                                   message: Throwable => Log.Message = exception => s"$exception - retrying...",
                                   restartSettings: RestartSettings = defaultRestartSettings
                                 )(
                                   implicit mat: Materializer,
                                   ec: ExecutionContext,
                                   log: IzLogger,
                                   pos: CodePositionMaterializer
                                 ): Future[Out] =
    retryWithBackoff(() => Source.future(future()), message, restartSettings).runWith(Sink.head)

  def retryWithBackoffSeq[T](
                              future: () => Future[Seq[T]],
                              message: Throwable => Log.Message = exception => s"$exception - retrying...",
                              restartSettings: RestartSettings = defaultRestartSettings
                            )(
                              implicit mat: Materializer,
                              ec: ExecutionContext,
                              log: IzLogger,
                              pos: CodePositionMaterializer
                            ): Source[T, NotUsed] =
    retryWithBackoff(() => Source.futureSource(future().map(Source(_))), message, restartSettings)
}
