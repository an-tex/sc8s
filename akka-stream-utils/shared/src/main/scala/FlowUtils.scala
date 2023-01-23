package net.sc8s.akka.stream

import akka.stream.scaladsl.{Flow, FlowOps, FlowWithContext, FlowWithContextOps, RunnableGraph, Source, SourceWithContext, SubFlow}
import akka.stream.{Materializer, RestartSettings}
import cats.instances.either._
import cats.instances.future._
import cats.instances.option._
import cats.instances.try_._
import cats.syntax.traverse._
import cats.{Monad, TraverseFilter}
import izumi.fundamentals.platform.language.CodePositionMaterializer
import izumi.logstage.api.{IzLogger, Log}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Right, Success, Try}

object FlowUtils {
  trait Wrapper[F[_]] {

    def mapAsync[A, B](fa: F[A])(f: A => Future[B])(implicit executionContext: ExecutionContext): Future[F[B]]

    def flatMapAsync[A, B](fa: F[A])(f: A => Future[F[B]])(implicit executionContext: ExecutionContext): Future[F[B]]

    def filterS[A](fa: F[A])(p: A => Boolean): Boolean

    def collectS[A, B](pf: PartialFunction[A, B]): PartialFunction[F[A], F[B]]

    def flatMapSource[A, B](f: A => Source[B, _]): F[A] => Source[F[B], _]
  }

  trait Wrapper2[F[_, _]] {

    def mapAsync[A, B, C](fa: F[A, B])(f: B => Future[C])(implicit executionContext: ExecutionContext): Future[F[A, C]]

    def flatMapAsync[A, B, C](fa: F[A, B])(f: B => Future[F[A, C]])(implicit executionContext: ExecutionContext): Future[F[A, C]]

    def filterS[A, B](fa: F[A, B])(p: B => Boolean): Boolean

    def collectS[A, B, C](pf: PartialFunction[B, C]): PartialFunction[F[A, B], F[A, C]]

    def flatMapSource[A, B1, B2](f: B1 => Source[B2, _]): F[A, B1] => Source[F[A, B2], _]
  }

  implicit object OptionWrapper extends Wrapper[Option] {

    override def mapAsync[A, B](fa: Option[A])(f: A => Future[B])(implicit executionContext: ExecutionContext) = fa.map(f).sequence

    override def flatMapAsync[A, B](fa: Option[A])(f: A => Future[Option[B]])(implicit executionContext: ExecutionContext) = fa.map(f).sequence.map(_.flatten)

    override def filterS[A](fa: Option[A])(p: A => Boolean) = fa.isEmpty || fa.exists(p)

    override def collectS[A, B](pf: PartialFunction[A, B]) = {
      case None => None
      case Some(value) if pf.isDefinedAt(value) => Some(pf(value))
    }

    override def flatMapSource[A, B](f: A => Source[B, _]) = {
      case Some(value) => f(value).map(Some(_))
      case None => Source.single(None)
    }
  }

  implicit object EitherWrapper extends Wrapper2[Either] {

    override def mapAsync[L, R, T](fa: Either[L, R])(f: R => Future[T])(implicit executionContext: ExecutionContext) = fa.map(f).sequence

    override def flatMapAsync[L, R, T](fa: Either[L, R])(f: R => Future[Either[L, T]])(implicit executionContext: ExecutionContext) = fa.map(f).sequence.map(_.flatten)

    override def filterS[L, R](fa: Either[L, R])(p: R => Boolean) = fa.isLeft || fa.exists(p)

    override def collectS[_, R, R2](pf: PartialFunction[R, R2]) = {
      case Left(l) => Left(l)
      case Right(right) if pf.isDefinedAt(right) => Right(pf(right))
    }

    override def flatMapSource[_, R1, R2](f: R1 => Source[R2, _]) = {
      case Right(value) => f(value).map(Right(_))
      case Left(value) => Source.single(Left(value))
    }
  }

  implicit object TryWrapper extends Wrapper[Try] {

    override def mapAsync[A, B](fa: Try[A])(f: A => Future[B])(implicit executionContext: ExecutionContext): Future[Try[B]] = fa.map(f).sequence

    override def flatMapAsync[A, B](fa: Try[A])(f: A => Future[Try[B]])(implicit executionContext: ExecutionContext): Future[Try[B]] = fa.map(f).sequence.map(_.flatten)

    override def filterS[A](fa: Try[A])(p: A => Boolean) = fa.isFailure || fa.toOption.exists(p)

    override def collectS[A, B](pf: PartialFunction[A, B]) = {
      case Failure(e) => Failure(e)
      case Success(value) if pf.isDefinedAt(value) => Success(pf(value))
    }

    override def flatMapSource[A, B](f: A => Source[B, _]) = {
      case Failure(exception) => Source.single(Failure(exception))
      case Success(value) => f(value).map(Success(_))
    }
  }

  // This needs "duplication" https://doc.akka.io/docs/akka/current/stream/stream-customize.html#extending-flow-operators-with-custom-operators :(

  object flow {
    trait MonadOpsF[Out, Mat, F[_]] {

      val s: FlowOps[F[Out], Mat]
      val monad: Monad[F]

      def mapF[Out2](f: Out => Out2): s.Repr[F[Out2]] = s.map(monad.lift(f))

      def flatMapF[Out2](f: Out => F[Out2]): s.Repr[F[Out2]] = s.map(monad.flatMap(_)(f))
    }

    implicit class SourceMonadOpsF[Out, Mat, F[_]](
                                                    val s: Source[F[Out], Mat]
                                                  )(
                                                    implicit val monad: Monad[F]
                                                  ) extends MonadOpsF[Out, Mat, F]

    implicit class FlowMonadOpsF[In, Out, Mat, F[_]](
                                                      val s: Flow[F[In], F[Out], Mat]
                                                    )(
                                                      implicit val monad: Monad[F]
                                                    ) extends MonadOpsF[Out, Mat, F]

    implicit class SubFlowMonadOpsF[Out, Mat, SubFlowF[+_], C, F[_]](
                                                                      val s: SubFlow[F[Out], Mat, SubFlowF, C]
                                                                    )(
                                                                      implicit val monad: Monad[F]
                                                                    ) extends MonadOpsF[Out, Mat, F]

    trait FilterOpsF[Out, Mat, F[_]] {
      val s: FlowOps[F[Out], Mat]
      val traverseFilter: TraverseFilter[F]

      def filterF(p: Out => Boolean): s.Repr[F[Out]] = s.map(traverseFilter.filter(_)(p))

      def collectF[Out2](pf: PartialFunction[Out, Out2]): s.Repr[F[Out2]] = s.map(traverseFilter.collect(_)(pf))
    }

    implicit class SourceFilterOpsF[Out, Mat, F[_]](
                                                     val s: Source[F[Out], Mat]
                                                   )(
                                                     implicit val traverseFilter: TraverseFilter[F]
                                                   ) extends FilterOpsF[Out, Mat, F]

    implicit class FlowFilterOpsF[In, Out, Mat, F[_]](
                                                       val s: Flow[F[In], F[Out], Mat]
                                                     )(
                                                       implicit val traverseFilter: TraverseFilter[F]
                                                     ) extends FilterOpsF[Out, Mat, F]

    implicit class SubFlowFilterOpsF[Out, Mat, SubFlowF[+_], C, F[_]](
                                                                       val s: SubFlow[F[Out], Mat, SubFlowF, C]
                                                                     )(
                                                                       implicit val traverseFilter: TraverseFilter[F]
                                                                     ) extends FilterOpsF[Out, Mat, F]

    trait IterableOnceOpsF[Out, Mat, F[Out] <: IterableOnce[Out]] {
      val s: FlowOps[F[Out], Mat]

      def flattenF: s.Repr[Out] = s.mapConcat(identity)
    }

    implicit class SourceIterableOnceOpsF[Out, Mat, F[Out] <: IterableOnce[Out]](
                                                                                  val s: Source[F[Out], Mat]
                                                                                ) extends IterableOnceOpsF[Out, Mat, F]

    implicit class FlowIterableOnceOpsF[In, Out, Mat, F[Out] <: IterableOnce[Out]](
                                                                                    val s: Flow[F[In], F[Out], Mat]
                                                                                  ) extends IterableOnceOpsF[Out, Mat, F]

    implicit class SubFlowIterableOnceOpsF[Out, Mat, SubFlowF[+_], C, F[Out] <: IterableOnce[Out]](
                                                                                                    val s: SubFlow[F[Out], Mat, SubFlowF, C]
                                                                                                  )(
                                                                                                    implicit val traverseFilter: TraverseFilter[F]
                                                                                                  ) extends IterableOnceOpsF[Out, Mat, F]

    trait OptionOpsF[Out, Mat] {
      val s: FlowOps[Option[Out], Mat]

      def groupByF[K](maxSubstreams: Int, f: Out => K): SubFlow[Option[Out], Mat, s.Repr, s.Closed] = {
        s.groupBy(maxSubstreams, _.map(f))
      }

      def foldF[Out2](zero: Out2)(f: (Out2, Out) => Out2): s.Repr[Option[Out2]] =
        s.fold(
          zero -> Seq.empty[Option[Out2]]
        ) {
          case ((acc, nones), maybeNext) => maybeNext match {
            case Some(value) => f(acc, value) -> nones
            case None => acc -> (nones :+ None)
          }
        }.mapConcat { case (value, nones) =>
          nones :+ Some(value)
        }

      def mapConcatF[Out2](f: Out => IterableOnce[Out2]): s.Repr[Option[Out2]] = s.mapConcat {
        case Some(value) => f(value).iterator.map(Some(_))
        case None => Seq(None)
      }
    }

    implicit class SourceOptionOpsF[Out, Mat](
                                               val s: Source[Option[Out], Mat]
                                             ) extends OptionOpsF[Out, Mat]

    implicit class FlowOptionOpsF[In, Out, Mat](
                                                 val s: Flow[Option[In], Option[Out], Mat]
                                               ) extends OptionOpsF[Out, Mat]

    implicit class SubFlowOptionOpsF[Out, Mat, SubFlowF[+_], C](
                                                                 val s: SubFlow[Option[Out], Mat, SubFlowF, C]
                                                               ) extends OptionOpsF[Out, Mat]

    trait EitherOpsF[OutL, OutR, Mat] {
      val s: FlowOps[Either[OutL, OutR], Mat]

      def filterOrElseF(p: OutR => Boolean, zero: => OutL): s.Repr[Either[OutL, OutR]] = s.map(_.filterOrElse(p, zero))

      def collectF[OutR2](pf: PartialFunction[OutR, OutR2])(zero: => OutL): s.Repr[Either[OutL, OutR2]] = s.map(_.flatMap {
        case right if pf.isDefinedAt(right) => Right(pf(right))
        case _ => Left(zero)
      })

      def flattenF: s.Repr[OutR] = s.collect {
        case Right(value) => value
      }

      def groupByF[K](maxSubstreams: Int, f: OutR => K): SubFlow[Either[OutL, OutR], Mat, s.Repr, s.Closed] = {
        s.groupBy(maxSubstreams, {
          case Right(value) => Some(f(value))
          case _ => None
        })
      }

      def foldF[OutR2](zero: OutR2)(f: (OutR2, OutR) => OutR2): s.Repr[Either[OutL, OutR2]] =
        s.fold(
          zero -> Seq.empty[Either[OutL, OutR2]]
        ) {
          case ((acc, lefts), next) => next match {
            case Right(value) => f(acc, value) -> lefts
            case Left(value) => acc -> (lefts :+ Left(value))
          }
        }.mapConcat { case (value, lefts) =>
          lefts :+ Right(value)
        }

      def mapConcatF[OutR2](f: OutR => IterableOnce[OutR2]): s.Repr[Either[OutL, OutR2]] = s.mapConcat {
        case Right(value) => f(value).iterator.map(Right(_))
        case Left(value) => Seq(Left(value))
      }
    }

    implicit class SourceEitherOpsF[OutL, OutR, Mat](
                                                      val s: Source[Either[OutL, OutR], Mat]
                                                    ) extends EitherOpsF[OutL, OutR, Mat]

    implicit class FlowEitherOpsF[In, OutL, OutR, Mat](
                                                        val s: Flow[In, Either[OutL, OutR], Mat]
                                                      ) extends EitherOpsF[OutL, OutR, Mat]

    implicit class SubFlowEitherOpsF[OutL, OutR, Mat, SubFlowF[+_], C, F[_]](
                                                                              val s: SubFlow[Either[OutL, OutR], Mat, SubFlowF, C]
                                                                            ) extends EitherOpsF[OutL, OutR, Mat]

    trait TryOpsF[Out, Mat] {
      val s: FlowOps[Try[Out], Mat]

      def filterOrElseF(p: Out => Boolean, zero: => Throwable): s.Repr[Try[Out]] = s.map(_.flatMap {
        case value if p(value) => Success(value)
        case _ => Failure(zero)
      })

      def collectF[Out2](pf: PartialFunction[Out, Out2])(zero: => Throwable): s.Repr[Try[Out2]] = s.map(_.flatMap {
        case value if pf.isDefinedAt(value) => Success(pf(value))
        case _ => Failure(zero)
      })

      def flattenF: s.Repr[Out] = s.collect {
        case Success(value) => value
      }

      def groupByF[K](maxSubstreams: Int, f: Out => K): SubFlow[Try[Out], Mat, s.Repr, s.Closed] = {
        s.groupBy(maxSubstreams, {
          case Success(value) => Some(f(value))
          case _ => None
        })
      }

      def foldF[Out2](zero: Out2)(f: (Out2, Out) => Out2): s.Repr[Try[Out2]] =
        s.fold(
          zero -> Seq.empty[Try[Out2]]
        ) {
          case ((acc, failures), maybeNext) => maybeNext match {
            case Success(value) => f(acc, value) -> failures
            case Failure(exception) => acc -> (failures :+ Failure(exception))
          }
        }.mapConcat { case (value, failures) =>
          failures :+ Success(value)
        }

      def mapConcatF[Out2](f: Out => IterableOnce[Out2]): s.Repr[Try[Out2]] = s.mapConcat {
        case Failure(exception) => Seq(Failure(exception))
        case Success(value) => f(value).iterator.map(Success(_))
      }
    }

    implicit class SourceTryOpsF[Out, Mat](
                                            val s: Source[Try[Out], Mat]
                                          ) extends TryOpsF[Out, Mat]

    implicit class FlowTryOpsF[In, Out, Mat](
                                              val s: Flow[In, Try[Out], Mat]
                                            ) extends TryOpsF[Out, Mat]

    implicit class SubFlowTryOpsF[Out, Mat, SubFlowF[+_], C, F[_]](
                                                                    val s: SubFlow[Try[Out], Mat, SubFlowF, C]
                                                                  ) extends TryOpsF[Out, Mat]

    trait AsyncOps[Out, Mat] {
      val s: FlowOps[Out, Mat]

      def mapAsyncRetryWithBackoff[Out2](parallelism: Int)(
        f: Out => Future[Out2],
        message: Out => Throwable => Log.Message = _ => exception => s"$exception - retrying...",
        restartSettings: RestartSettings = RetryUtils.defaultRestartSettings
      )(
                                          implicit mat: Materializer,
                                          ec: ExecutionContext,
                                          log: IzLogger,
                                          pos: CodePositionMaterializer
                                        ): s.Repr[Out2] =
        s.mapAsync(parallelism)({ element =>
          RetryUtils.retryWithBackoffFuture(() => f(element), message(element), restartSettings)
        })

      def mapAsyncUnorderedRetryWithBackoff[Out2](parallelism: Int)(
        f: Out => Future[Out2],
        message: Out => Throwable => Log.Message = _ => exception => s"$exception - retrying...",
        restartSettings: RestartSettings = RetryUtils.defaultRestartSettings
      )(
                                                   implicit mat: Materializer,
                                                   ec: ExecutionContext,
                                                   log: IzLogger,
                                                   pos: CodePositionMaterializer
                                                 ): s.Repr[Out2] =
        s.mapAsyncUnordered(parallelism)({ element =>
          RetryUtils.retryWithBackoffFuture(() => f(element), message(element), restartSettings)
        })
    }

    implicit class AsyncSourceOps[Out, Mat](
                                             val s: Source[Out, Mat]
                                           ) extends AsyncOps[Out, Mat]

    implicit class AsyncFlowOps[In, Out, Mat](
                                               val s: Flow[In, Out, Mat]
                                             ) extends AsyncOps[Out, Mat]

    implicit class AsyncSubFlowOps[Out, Mat, SubFlowF[+_], C](
                                                               val s: SubFlow[Out, Mat, SubFlowF, C]
                                                             ) extends AsyncOps[Out, Mat]

    trait WrapperOpsF[Out, Mat, F[_]] {
      val s: FlowOps[F[Out], Mat]
      val wrapper: Wrapper[F]

      def mapAsyncF[Out2](parallelism: Int)(f: Out => Future[Out2])(implicit executionContext: ExecutionContext): s.Repr[F[Out2]] =
        s.mapAsync(parallelism)(wrapper.mapAsync(_)(f))

      def flatMapAsyncF[Out2](parallelism: Int)(f: Out => Future[F[Out2]])(implicit executionContext: ExecutionContext): s.Repr[F[Out2]] =
        s.mapAsync(parallelism)(wrapper.flatMapAsync(_)(f))

      def mapAsyncUnorderedF[Out2](parallelism: Int)(f: Out => Future[Out2])(implicit executionContext: ExecutionContext): s.Repr[F[Out2]] =
        s.mapAsyncUnordered(parallelism)(wrapper.mapAsync(_)(f))

      def flatMapAsyncUnorderedF[Out2](parallelism: Int)(f: Out => Future[F[Out2]])(implicit executionContext: ExecutionContext): s.Repr[F[Out2]] =
        s.mapAsyncUnordered(parallelism)(wrapper.flatMapAsync(_)(f))

      def filterS(p: Out => Boolean): s.Repr[F[Out]] = s.filter(wrapper.filterS(_)(p))

      def collectS[Out2](pf: PartialFunction[Out, Out2]): s.Repr[F[Out2]] = s.collect(wrapper.collectS(pf))

      def flatMapConcatF[Out2, Mat2](f: Out => Source[Out2, Mat2]): s.Repr[F[Out2]] = s.flatMapConcat(wrapper.flatMapSource(f))

      def flatMapMergeF[Out2, Mat2](breadth: Int, f: Out => Source[Out2, Mat2]): s.Repr[F[Out2]] = s.flatMapMerge(breadth, wrapper.flatMapSource(f))

      def mapAsyncRetryWithBackoffF[Out2](parallelism: Int)(
        f: Out => Future[Out2],
        message: Out => Throwable => Log.Message = _ => exception => s"$exception - retrying...",
        restartSettings: RestartSettings = RetryUtils.defaultRestartSettings
      )(
                                           implicit mat: Materializer,
                                           ec: ExecutionContext,
                                           log: IzLogger,
                                           pos: CodePositionMaterializer
                                         ): s.Repr[F[Out2]] =
        mapAsyncF(parallelism)({ element =>
          RetryUtils.retryWithBackoffFuture(() => f(element), message(element), restartSettings)
        })

      def flatMapAsyncRetryWithBackoffF[Out2](parallelism: Int)(
        f: Out => Future[F[Out2]],
        message: Out => Throwable => Log.Message = _ => exception => s"$exception - retrying...",
        restartSettings: RestartSettings = RetryUtils.defaultRestartSettings
      )(
                                               implicit mat: Materializer,
                                               ec: ExecutionContext,
                                               log: IzLogger,
                                               pos: CodePositionMaterializer
                                             ): s.Repr[F[Out2]] =
        flatMapAsyncF(parallelism)({ element =>
          RetryUtils.retryWithBackoffFuture(() => f(element), message(element), restartSettings)
        })

      def mapAsyncUnorderedRetryWithBackoffF[Out2](parallelism: Int)(
        f: Out => Future[Out2],
        message: Out => Throwable => Log.Message = _ => exception => s"$exception - retrying...",
        restartSettings: RestartSettings = RetryUtils.defaultRestartSettings
      )(
                                                    implicit mat: Materializer,
                                                    ec: ExecutionContext,
                                                    log: IzLogger,
                                                    pos: CodePositionMaterializer
                                                  ): s.Repr[F[Out2]] =
        mapAsyncUnorderedF(parallelism)({ element =>
          RetryUtils.retryWithBackoffFuture(() => f(element), message(element), restartSettings)
        })

      def flatMapAsyncUnorderedRetryWithBackoffF[Out2](parallelism: Int)(
        f: Out => Future[F[Out2]],
        message: Out => Throwable => Log.Message = _ => exception => s"$exception - retrying...",
        restartSettings: RestartSettings = RetryUtils.defaultRestartSettings
      )(
                                                        implicit mat: Materializer,
                                                        ec: ExecutionContext,
                                                        log: IzLogger,
                                                        pos: CodePositionMaterializer
                                                      ): s.Repr[F[Out2]] =
        flatMapAsyncUnorderedF(parallelism)({ element =>
          RetryUtils.retryWithBackoffFuture(() => f(element), message(element), restartSettings)
        })
    }

    implicit class SourceWrapperOpsF[Out, Mat, F[_]](
                                                      val s: Source[F[Out], Mat]
                                                    )(
                                                      implicit val wrapper: Wrapper[F]
                                                    ) extends WrapperOpsF[Out, Mat, F]

    implicit class FlowWrapperOpsF[In, Out, Mat, F[_]](
                                                        val s: Flow[F[In], F[Out], Mat]
                                                      )(
                                                        implicit val wrapper: Wrapper[F]
                                                      ) extends WrapperOpsF[Out, Mat, F]

    implicit class SubFlowWrapperOpsF[Out, Mat, SubFlowF[+_], C, F[_]](
                                                                        val s: SubFlow[F[Out], Mat, SubFlowF, C]
                                                                      )(
                                                                        implicit val wrapper: Wrapper[F]
                                                                      ) extends WrapperOpsF[Out, Mat, F]

    trait Wrapper2OpsF[OutA, OutB, Mat, F[_, _]] {
      val s: FlowOps[F[OutA, OutB], Mat]
      val wrapper: Wrapper2[F]

      def mapAsyncF[Out2](parallelism: Int)(f: OutB => Future[Out2])(implicit executionContext: ExecutionContext): s.Repr[F[OutA, Out2]] =
        s.mapAsync(parallelism)(wrapper.mapAsync(_)(f))

      def flatMapAsyncF[Out2](parallelism: Int)(f: OutB => Future[F[OutA, Out2]])(implicit executionContext: ExecutionContext): s.Repr[F[OutA, Out2]] =
        s.mapAsync(parallelism)(wrapper.flatMapAsync(_)(f))

      def mapAsyncUnorderedF[Out2](parallelism: Int)(f: OutB => Future[Out2])(implicit executionContext: ExecutionContext): s.Repr[F[OutA, Out2]] =
        s.mapAsyncUnordered(parallelism)(wrapper.mapAsync(_)(f))

      def flatMapAsyncUnorderedF[Out2](parallelism: Int)(f: OutB => Future[F[OutA, Out2]])(implicit executionContext: ExecutionContext): s.Repr[F[OutA, Out2]] =
        s.mapAsyncUnordered(parallelism)(wrapper.flatMapAsync(_)(f))

      def filterS(p: OutB => Boolean): s.Repr[F[OutA, OutB]] = s.filter(wrapper.filterS(_)(p))

      def collectS[Out2](pf: PartialFunction[OutB, Out2]): s.Repr[F[OutA, Out2]] = s.collect(wrapper.collectS(pf))

      def flatMapConcatF[Out2, Mat2](f: OutB => Source[Out2, Mat2]): s.Repr[F[OutA, Out2]] = s.flatMapConcat(wrapper.flatMapSource(f))

      def flatMapMergeF[Out2, Mat2](breadth: Int, f: OutB => Source[Out2, Mat2]): s.Repr[F[OutA, Out2]] = s.flatMapMerge(breadth, wrapper.flatMapSource(f))

      def mapAsyncRetryWithBackoffF[Out2](parallelism: Int)(
        f: OutB => Future[Out2],
        message: OutB => Throwable => Log.Message = _ => exception => s"$exception - retrying...",
        restartSettings: RestartSettings = RetryUtils.defaultRestartSettings
      )(
                                           implicit mat: Materializer,
                                           ec: ExecutionContext,
                                           log: IzLogger,
                                           pos: CodePositionMaterializer
                                         ): s.Repr[F[OutA, Out2]] =
        mapAsyncF(parallelism)({ element =>
          RetryUtils.retryWithBackoffFuture(() => f(element), message(element), restartSettings)
        })

      def flatMapAsyncRetryWithBackoffF[Out2](parallelism: Int)(
        f: OutB => Future[F[OutA, Out2]],
        message: OutB => Throwable => Log.Message = _ => exception => s"$exception - retrying...",
        restartSettings: RestartSettings = RetryUtils.defaultRestartSettings
      )(
                                               implicit mat: Materializer,
                                               ec: ExecutionContext,
                                               log: IzLogger,
                                               pos: CodePositionMaterializer
                                             ): s.Repr[F[OutA, Out2]] =
        flatMapAsyncF(parallelism)({ element =>
          RetryUtils.retryWithBackoffFuture(() => f(element), message(element), restartSettings)
        })

      def mapAsyncUnorderedRetryWithBackoffF[Out2](parallelism: Int)(
        f: OutB => Future[Out2],
        message: OutB => Throwable => Log.Message = _ => exception => s"$exception - retrying...",
        restartSettings: RestartSettings = RetryUtils.defaultRestartSettings
      )(
                                                    implicit mat: Materializer,
                                                    ec: ExecutionContext,
                                                    log: IzLogger,
                                                    pos: CodePositionMaterializer
                                                  ): s.Repr[F[OutA, Out2]] =
        mapAsyncUnorderedF(parallelism)({ element =>
          RetryUtils.retryWithBackoffFuture(() => f(element), message(element), restartSettings)
        })

      def flatMapAsyncUnorderedRetryWithBackoffF[Out2](parallelism: Int)(
        f: OutB => Future[F[OutA, Out2]],
        message: OutB => Throwable => Log.Message = _ => exception => s"$exception - retrying...",
        restartSettings: RestartSettings = RetryUtils.defaultRestartSettings
      )(
                                                        implicit mat: Materializer,
                                                        ec: ExecutionContext,
                                                        log: IzLogger,
                                                        pos: CodePositionMaterializer
                                                      ): s.Repr[F[OutA, Out2]] =
        flatMapAsyncUnorderedF(parallelism)({ element =>
          RetryUtils.retryWithBackoffFuture(() => f(element), message(element), restartSettings)
        })
    }

    implicit class SourceWrapper2OpsF[OutA, OutB, Mat, F[_, _]](
                                                                 val s: Source[F[OutA, OutB], Mat]
                                                               )(
                                                                 implicit val wrapper: Wrapper2[F]
                                                               ) extends Wrapper2OpsF[OutA, OutB, Mat, F]

    implicit class FlowWrapper2OpsF[In, OutA, OutB, Mat, F[_, _]](
                                                                   val s: Flow[In, F[OutA, OutB], Mat]
                                                                 )(
                                                                   implicit val wrapper: Wrapper2[F]
                                                                 ) extends Wrapper2OpsF[OutA, OutB, Mat, F]

    implicit class SubFlowWrapper2OpsF[OutA, OutB, Mat, SubFlowF[+_], C, F[_, _]](
                                                                                   val s: SubFlow[F[OutA, OutB], Mat, SubFlowF, C]
                                                                                 )(
                                                                                   implicit val wrapper: Wrapper2[F]
                                                                                 ) extends Wrapper2OpsF[OutA, OutB, Mat, F]
  }

  // groupBy, fold and mapAsyncUnordered*, flatMapConcat, flatMapConcatMerge not possible due to https://doc.akka.io/docs/akka/current/stream/stream-context.html#restrictions , hence SubFlow not available either
  object flowWithContext {
    trait MonadOpsF[Out, Ctx, Mat, F[_]] {

      val s: FlowWithContextOps[F[Out], Ctx, Mat]
      val monad: Monad[F]

      def mapF[Out2](f: Out => Out2): s.Repr[F[Out2], Ctx] = s.map(monad.lift(f))

      def flatMapF[Out2](f: Out => F[Out2]): s.Repr[F[Out2], Ctx] = s.map(monad.flatMap(_)(f))
    }

    implicit class SourceMonadOpsF[Out, Ctx, Mat, F[_]](
                                                         val s: SourceWithContext[F[Out], Ctx, Mat]
                                                       )(
                                                         implicit val monad: Monad[F]
                                                       ) extends MonadOpsF[Out, Ctx, Mat, F]

    implicit class FlowMonadOpsF[In, CtxIn, Out, CtxOut, Mat, F[_]](
                                                                     val s: FlowWithContext[F[In], CtxIn, F[Out], CtxOut, Mat]
                                                                   )(
                                                                     implicit val monad: Monad[F]
                                                                   ) extends MonadOpsF[Out, CtxOut, Mat, F]

    trait FilterOpsF[Out, Ctx, Mat, F[_]] {
      val s: FlowWithContextOps[F[Out], Ctx, Mat]
      val traverseFilter: TraverseFilter[F]

      def filterF(p: Out => Boolean): s.Repr[F[Out], Ctx] = s.map(traverseFilter.filter(_)(p))

      def collectF[Out2](pf: PartialFunction[Out, Out2]): s.Repr[F[Out2], Ctx] = s.map(traverseFilter.collect(_)(pf))
    }

    implicit class SourceFilterOpsF[Out, Ctx, Mat, F[_]](
                                                          val s: SourceWithContext[F[Out], Ctx, Mat]
                                                        )(
                                                          implicit val traverseFilter: TraverseFilter[F]
                                                        ) extends FilterOpsF[Out, Ctx, Mat, F]

    implicit class FlowFilterOpsF[In, CtxIn, Out, CtxOut, Mat, F[_]](
                                                                      val s: FlowWithContext[F[In], CtxIn, F[Out], CtxOut, Mat]
                                                                    )(
                                                                      implicit val traverseFilter: TraverseFilter[F]
                                                                    ) extends FilterOpsF[Out, CtxOut, Mat, F]

    trait IterableOnceOpsF[Out, Ctx, Mat, F[Out] <: IterableOnce[Out]] {
      val s: FlowWithContextOps[F[Out], Ctx, Mat]

      def flattenF: s.Repr[Out, Ctx] = s.mapConcat(identity)
    }

    implicit class SourceIterableOnceOpsF[Out, Ctx, Mat, F[Out] <: IterableOnce[Out]](
                                                                                       val s: SourceWithContext[F[Out], Ctx, Mat]
                                                                                     ) extends IterableOnceOpsF[Out, Ctx, Mat, F]

    implicit class FlowIterableOnceOpsF[In, CtxIn, Out, CtxOut, Mat, F[Out] <: IterableOnce[Out]](
                                                                                                   val s: FlowWithContext[F[In], CtxIn, F[Out], CtxOut, Mat]
                                                                                                 ) extends IterableOnceOpsF[Out, CtxOut, Mat, F]

    trait OptionOpsF[Out, Ctx, Mat] {
      val s: FlowWithContextOps[Option[Out], Ctx, Mat]

      def mapConcatF[Out2](f: Out => IterableOnce[Out2]): s.Repr[Option[Out2], Ctx] = s.mapConcat {
        case Some(value) => f(value).iterator.map(Some(_))
        case None => Seq(None)
      }
    }

    implicit class SourceOptionOpsF[Out, Ctx, Mat](
                                                    val s: SourceWithContext[Option[Out], Ctx, Mat]
                                                  ) extends OptionOpsF[Out, Ctx, Mat]

    implicit class FlowOptionOpsF[In, CtxIn, Out, CtxOut, Mat](
                                                                val s: FlowWithContext[Option[In], CtxIn, Option[Out], CtxOut, Mat]
                                                              ) extends OptionOpsF[Out, CtxOut, Mat]

    trait EitherOpsF[OutL, OutR, Ctx, Mat] {
      val s: FlowWithContextOps[Either[OutL, OutR], Ctx, Mat]

      def filterOrElseF(p: OutR => Boolean, zero: => OutL): s.Repr[Either[OutL, OutR], Ctx] = s.map(_.filterOrElse(p, zero))

      def collectF[OutR2](pf: PartialFunction[OutR, OutR2])(zero: => OutL): s.Repr[Either[OutL, OutR2], Ctx] = s.map(_.flatMap {
        case right if pf.isDefinedAt(right) => Right(pf(right))
        case _ => Left(zero)
      })

      def flattenF: s.Repr[OutR, Ctx] = s.collect {
        case Right(value) => value
      }

      def mapConcatF[OutR2](f: OutR => IterableOnce[OutR2]): s.Repr[Either[OutL, OutR2], Ctx] = s.mapConcat {
        case Right(value) => f(value).iterator.map(Right(_))
        case Left(value) => Seq(Left(value))
      }
    }

    implicit class SourceEitherOpsF[OutL, OutR, Ctx, Mat](
                                                           val s: SourceWithContext[Either[OutL, OutR], Ctx, Mat]
                                                         ) extends EitherOpsF[OutL, OutR, Ctx, Mat]

    implicit class FlowEitherOpsF[In, CtxIn, OutL, OutR, CtxOut, Mat](
                                                                       val s: FlowWithContext[In, CtxIn, Either[OutL, OutR], CtxOut, Mat]
                                                                     ) extends EitherOpsF[OutL, OutR, CtxOut, Mat]

    trait TryOpsF[Out, Ctx, Mat] {
      val s: FlowWithContextOps[Try[Out], Ctx, Mat]

      def filterOrElseF(p: Out => Boolean, zero: => Throwable): s.Repr[Try[Out], Ctx] = s.map(_.flatMap {
        case value if p(value) => Success(value)
        case _ => Failure(zero)
      })

      def collectF[Out2](pf: PartialFunction[Out, Out2])(zero: => Throwable): s.Repr[Try[Out2], Ctx] = s.map(_.flatMap {
        case value if pf.isDefinedAt(value) => Success(pf(value))
        case _ => Failure(zero)
      })

      def flattenF: s.Repr[Out, Ctx] = s.collect {
        case Success(value) => value
      }

      def mapConcatF[Out2](f: Out => IterableOnce[Out2]): s.Repr[Try[Out2], Ctx] = s.mapConcat {
        case Failure(exception) => Seq(Failure(exception))
        case Success(value) => f(value).iterator.map(Success(_))
      }
    }

    implicit class SourceTryOpsF[Out, Ctx, Mat](
                                                 val s: SourceWithContext[Try[Out], Ctx, Mat]
                                               ) extends TryOpsF[Out, Ctx, Mat]

    implicit class FlowTryOpsF[In, CtxIn, Out, CtxOut, Mat](
                                                             val s: FlowWithContext[In, CtxIn, Try[Out], CtxOut, Mat]
                                                           ) extends TryOpsF[Out, CtxOut, Mat]

    trait AsyncOps[Out, Ctx, Mat] {
      val s: FlowWithContextOps[Out, Ctx, Mat]

      def mapAsyncRetryWithBackoff[Out2](parallelism: Int)(
        f: Out => Future[Out2],
        message: Out => Throwable => Log.Message = _ => exception => s"$exception - retrying...",
        restartSettings: RestartSettings = RetryUtils.defaultRestartSettings
      )(
                                          implicit mat: Materializer,
                                          ec: ExecutionContext,
                                          log: IzLogger,
                                          pos: CodePositionMaterializer
                                        ): s.Repr[Out2, Ctx] =
        s.mapAsync(parallelism)({ element =>
          RetryUtils.retryWithBackoffFuture(() => f(element), message(element), restartSettings)
        })
    }

    implicit class AsyncSourceOps[Out, Ctx, Mat](
                                                  val s: SourceWithContext[Out, Ctx, Mat]
                                                ) extends AsyncOps[Out, Ctx, Mat]

    implicit class AsyncFlowOps[In, CtxIn, Out, CtxOut, Mat](
                                                              val s: FlowWithContext[In, CtxIn, Out, CtxOut, Mat]
                                                            ) extends AsyncOps[Out, CtxOut, Mat]

    trait WrapperOpsF[Out, Ctx, Mat, F[_]] {
      val s: FlowWithContextOps[F[Out], Ctx, Mat]
      val wrapper: Wrapper[F]

      def mapAsyncF[Out2](parallelism: Int)(f: Out => Future[Out2])(implicit executionContext: ExecutionContext): s.Repr[F[Out2], Ctx] =
        s.mapAsync(parallelism)(wrapper.mapAsync(_)(f))

      def flatMapAsyncF[Out2](parallelism: Int)(f: Out => Future[F[Out2]])(implicit executionContext: ExecutionContext): s.Repr[F[Out2], Ctx] =
        s.mapAsync(parallelism)(wrapper.flatMapAsync(_)(f))

      def filterS(p: Out => Boolean): s.Repr[F[Out], Ctx] = s.filter(wrapper.filterS(_)(p))

      def collectS[Out2](pf: PartialFunction[Out, Out2]): s.Repr[F[Out2], Ctx] = s.collect(wrapper.collectS(pf))

      def mapAsyncRetryWithBackoffF[Out2](parallelism: Int)(
        f: Out => Future[Out2],
        message: Out => Throwable => Log.Message = _ => exception => s"$exception - retrying...",
        restartSettings: RestartSettings = RetryUtils.defaultRestartSettings
      )(
                                           implicit mat: Materializer,
                                           ec: ExecutionContext,
                                           log: IzLogger,
                                           pos: CodePositionMaterializer
                                         ): s.Repr[F[Out2], Ctx] =
        mapAsyncF(parallelism)({ element =>
          RetryUtils.retryWithBackoffFuture(() => f(element), message(element), restartSettings)
        })

      def flatMapAsyncRetryWithBackoffF[Out2](parallelism: Int)(
        f: Out => Future[F[Out2]],
        message: Out => Throwable => Log.Message = _ => exception => s"$exception - retrying...",
        restartSettings: RestartSettings = RetryUtils.defaultRestartSettings
      )(
                                               implicit mat: Materializer,
                                               ec: ExecutionContext,
                                               log: IzLogger,
                                               pos: CodePositionMaterializer
                                             ): s.Repr[F[Out2], Ctx] =
        flatMapAsyncF(parallelism)({ element =>
          RetryUtils.retryWithBackoffFuture(() => f(element), message(element), restartSettings)
        })
    }

    implicit class SourceWrapperOpsF[Out, Ctx, Mat, F[_]](
                                                           val s: SourceWithContext[F[Out], Ctx, Mat]
                                                         )(
                                                           implicit val wrapper: Wrapper[F]
                                                         ) extends WrapperOpsF[Out, Ctx, Mat, F]

    implicit class FlowWrapperOpsF[In, CtxIn, Out, CtxOut, Mat, F[_]](
                                                                       val s: FlowWithContext[F[In], CtxIn, F[Out], CtxOut, Mat]
                                                                     )(
                                                                       implicit val wrapper: Wrapper[F]
                                                                     ) extends WrapperOpsF[Out, CtxOut, Mat, F]

    trait Wrapper2OpsF[OutA, OutB, Ctx, Mat, F[_, _]] {
      val s: FlowWithContextOps[F[OutA, OutB], Ctx, Mat]
      val wrapper: Wrapper2[F]

      def mapAsyncF[Out2](parallelism: Int)(f: OutB => Future[Out2])(implicit executionContext: ExecutionContext): s.Repr[F[OutA, Out2], Ctx] =
        s.mapAsync(parallelism)(wrapper.mapAsync(_)(f))

      def flatMapAsyncF[Out2](parallelism: Int)(f: OutB => Future[F[OutA, Out2]])(implicit executionContext: ExecutionContext): s.Repr[F[OutA, Out2], Ctx] =
        s.mapAsync(parallelism)(wrapper.flatMapAsync(_)(f))

      def filterS(p: OutB => Boolean): s.Repr[F[OutA, OutB], Ctx] = s.filter(wrapper.filterS(_)(p))

      def collectS[Out2](pf: PartialFunction[OutB, Out2]): s.Repr[F[OutA, Out2], Ctx] = s.collect(wrapper.collectS(pf))

      def mapAsyncRetryWithBackoffF[Out2](parallelism: Int)(
        f: OutB => Future[Out2],
        message: OutB => Throwable => Log.Message = _ => exception => s"$exception - retrying...",
        restartSettings: RestartSettings = RetryUtils.defaultRestartSettings
      )(
                                           implicit mat: Materializer,
                                           ec: ExecutionContext,
                                           log: IzLogger,
                                           pos: CodePositionMaterializer
                                         ): s.Repr[F[OutA, Out2], Ctx] =
        mapAsyncF(parallelism)({ element =>
          RetryUtils.retryWithBackoffFuture(() => f(element), message(element), restartSettings)
        })

      def flatMapAsyncRetryWithBackoffF[Out2](parallelism: Int)(
        f: OutB => Future[F[OutA, Out2]],
        message: OutB => Throwable => Log.Message = _ => exception => s"$exception - retrying...",
        restartSettings: RestartSettings = RetryUtils.defaultRestartSettings
      )(
                                               implicit mat: Materializer,
                                               ec: ExecutionContext,
                                               log: IzLogger,
                                               pos: CodePositionMaterializer
                                             ): s.Repr[F[OutA, Out2], Ctx] =
        flatMapAsyncF(parallelism)({ element =>
          RetryUtils.retryWithBackoffFuture(() => f(element), message(element), restartSettings)
        })
    }

    implicit class SourceWrapper2OpsF[OutA, OutB, Ctx, Mat, F[_, _]](
                                                                      val s: SourceWithContext[F[OutA, OutB], Ctx, Mat]
                                                                    )(
                                                                      implicit val wrapper: Wrapper2[F]
                                                                    ) extends Wrapper2OpsF[OutA, OutB, Ctx, Mat, F]

    implicit class FlowWrapper2OpsF[In, CtxIn, OutA, OutB, CtxOut, Mat, F[_, _]](
                                                                                  val s: FlowWithContext[In, CtxIn, F[OutA, OutB], CtxOut, Mat]
                                                                                )(
                                                                                  implicit val wrapper: Wrapper2[F]
                                                                                ) extends Wrapper2OpsF[OutA, OutB, CtxOut, Mat, F]
  }
}
