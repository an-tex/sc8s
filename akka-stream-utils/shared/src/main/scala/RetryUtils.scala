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
                              tag: String = "operationFailed",
                              message: Throwable => Log.Message = exception => s"$exception",
                              restartSettings: RestartSettings = defaultRestartSettings,
                              failWithoutRetry: PartialFunction[Throwable, Boolean] = PartialFunction.empty,
                            )(
                              implicit mat: Materializer,
                              ec: ExecutionContext,
                              log: IzLogger,
                              pos: CodePositionMaterializer
                            ): Source[T, NotUsed] = {
    def createCommonSource = {
      val (eventualDone, restartingSource) = source().watchTermination()(Keep.right).preMaterialize()
      eventualDone.onComplete {
        case Failure(exception) =>
          // this log will also appear for the last retry which won't be retried. can't treat it separately as retry decision is handled inside RestartSource
          log
            // additional context for filtering
            .withCustomContext("retryWithBackoff" -> true)
            .log(Log.Level.Warn)(Log.Message(s"${s"${tag}Retrying" -> "tag"}") + message(exception))
        case _ =>
      }
      restartingSource
    }

    // optimization to avoid unnecessary Either allocation
    val restartingSource = if (failWithoutRetry == PartialFunction.empty)
      RestartSource.onFailuresWithBackoff(restartSettings) { () =>
        createCommonSource
      }
    else
      RestartSource.onFailuresWithBackoff(restartSettings) { () =>
        createCommonSource
          .map(Right(_))
          .recover {
            case exception if failWithoutRetry.unapply(exception).contains(true) => Left(exception)
          }
      }.map {
        case Right(value) => value
        case Left(exception) => throw exception
      }

    val (eventualDone, supervisorSource) = restartingSource.watchTermination()(Keep.right).preMaterialize()

    eventualDone.onComplete {
      case Failure(exception) =>
        log.log(Log.Level.Error)(Log.Message(s"$tag") + message(exception))
      case _ =>
    }
    supervisorSource
  }

  def retryWithBackoffF[T](
                            future: () => Future[T],
                            tag: String = "operationFailed",
                            message: Throwable => Log.Message = exception => s"$exception",
                            restartSettings: RestartSettings = defaultRestartSettings,
                            failWithoutRetry: PartialFunction[Throwable, Boolean] = PartialFunction.empty,
                          )(
                            implicit mat: Materializer,
                            ec: ExecutionContext,
                            log: IzLogger,
                            pos: CodePositionMaterializer
                          ): Source[T, NotUsed] =
    retryWithBackoff(() => Source.future(future()), tag, message, restartSettings, failWithoutRetry)

  def retryWithBackoffFuture[Out](
                                   future: () => Future[Out],
                                   tag: String = "operationFailed",
                                   message: Throwable => Log.Message = exception => s"$exception",
                                   restartSettings: RestartSettings = defaultRestartSettings,
                                   failWithoutRetry: PartialFunction[Throwable, Boolean] = PartialFunction.empty,
                                 )(
                                   implicit mat: Materializer,
                                   ec: ExecutionContext,
                                   log: IzLogger,
                                   pos: CodePositionMaterializer
                                 ): Future[Out] =
    retryWithBackoff(() => Source.future(future()), tag, message, restartSettings, failWithoutRetry).runWith(Sink.head)

  def retryWithBackoffSeq[T](
                              future: () => Future[Seq[T]],
                              tag: String = "operationFailed",
                              message: Throwable => Log.Message = exception => s"$exception",
                              restartSettings: RestartSettings = defaultRestartSettings,
                              failWithoutRetry: PartialFunction[Throwable, Boolean] = PartialFunction.empty,
                            )(
                              implicit mat: Materializer,
                              ec: ExecutionContext,
                              log: IzLogger,
                              pos: CodePositionMaterializer
                            ): Source[T, NotUsed] =
    retryWithBackoff(() => Source.futureSource(future().map(Source(_))), tag, message, restartSettings, failWithoutRetry)
}
