package net.sc8s.akka.stream

import akka.stream.scaladsl.{Broadcast, Flow, FlowOps, FlowWithContext, FlowWithContextOps, GraphDSL, Merge, Source, SourceWithContext, SubFlow}
import akka.stream.{FlowShape, Materializer, RestartSettings}
import cats.implicits.{catsSyntaxEitherId, catsSyntaxOptionId}
import cats.instances.either._
import cats.instances.future._
import cats.instances.option._
import cats.instances.try_._
import cats.syntax.traverse._
import cats.{Monad, TraverseFilter}
import izumi.fundamentals.platform.language.CodePositionMaterializer
import izumi.logstage.api.{IzLogger, Log}
import net.sc8s.akka.stream.implicits.{FlowEitherOps, FlowIterableOnceOps}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Right, Success, Try}

object StreamOps {
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

  trait SourceImplicits
    extends source.MonadOpsImplicits
      with source.FilterOpsImplicits
      with source.IterableOnceOpsImplicits
      with source.OptionOpsImplicits
      with source.EitherOpsImplicits
      with source.TryOpsImplicits
      with source.AsyncOpsImplicits
      with source.WrapperOpsImplicits
      with source.Wrapper2OpsImplicits

  trait SourceWithContextImplicits
    extends sourceWithContext.MonadOpsImplicits
      with sourceWithContext.FilterOpsImplicits
      with sourceWithContext.IterableOnceOpsImplicits
      with sourceWithContext.OptionOpsImplicits
      with sourceWithContext.EitherOpsImplicits
      with sourceWithContext.TryOpsImplicits
      with sourceWithContext.AsyncOpsImplicits
      with sourceWithContext.WrapperOpsImplicits
      with sourceWithContext.Wrapper2OpsImplicits


  // This needs "duplication" https://doc.akka.io/docs/akka/current/stream/stream-customize.html#extending-flow-operators-with-custom-operators :(

  object source {
    trait MonadOps[Out, Mat, F[_]] {

      val s: FlowOps[F[Out], Mat]
      val monad: Monad[F]

      def mapF[Out2](f: Out => Out2): s.Repr[F[Out2]] = s.map(monad.lift(f))

      def flatMapF[Out2](f: Out => F[Out2]): s.Repr[F[Out2]] = s.map(monad.flatMap(_)(f))
    }

    trait MonadOpsImplicits {
      implicit class SourceMonadOps[Out, Mat, F[_]](
                                                     val s: Source[F[Out], Mat]
                                                   )(
                                                     implicit val monad: Monad[F]
                                                   ) extends MonadOps[Out, Mat, F]

      implicit class FlowMonadOps[In, Out, Mat, F[_]](
                                                       val s: Flow[In, F[Out], Mat]
                                                     )(
                                                       implicit val monad: Monad[F]
                                                     ) extends MonadOps[Out, Mat, F]

      implicit class SubFlowMonadOps[Out, Mat, SubFlowF[+_], C, F[_]](
                                                                       val s: SubFlow[F[Out], Mat, SubFlowF, C]
                                                                     )(
                                                                       implicit val monad: Monad[F]
                                                                     ) extends MonadOps[Out, Mat, F]
    }

    trait FilterOps[Out, Mat, F[_]] {
      val s: FlowOps[F[Out], Mat]
      val traverseFilter: TraverseFilter[F]

      def filterF(p: Out => Boolean): s.Repr[F[Out]] = s.map(traverseFilter.filter(_)(p))

      def collectF[Out2](pf: PartialFunction[Out, Out2]): s.Repr[F[Out2]] = s.map(traverseFilter.collect(_)(pf))
    }

    trait FilterOpsImplicits {
      implicit class SourceFilterOps[Out, Mat, F[_]](
                                                      val s: Source[F[Out], Mat]
                                                    )(
                                                      implicit val traverseFilter: TraverseFilter[F]
                                                    ) extends FilterOps[Out, Mat, F]

      implicit class FlowFilterOps[In, Out, Mat, F[_]](
                                                        val s: Flow[In, F[Out], Mat]
                                                      )(
                                                        implicit val traverseFilter: TraverseFilter[F]
                                                      ) extends FilterOps[Out, Mat, F]

      implicit class SubFlowFilterOps[Out, Mat, SubFlowF[+_], C, F[_]](
                                                                        val s: SubFlow[F[Out], Mat, SubFlowF, C]
                                                                      )(
                                                                        implicit val traverseFilter: TraverseFilter[F]
                                                                      ) extends FilterOps[Out, Mat, F]
    }

    trait IterableOnceOps[Out, Mat, F[Out] <: IterableOnce[Out]] {
      val s: FlowOps[F[Out], Mat]

      def flattenF: s.Repr[Out] = s.mapConcat(identity)

      def alsoToF[Mat2](that: akka.stream.Graph[akka.stream.SinkShape[Out], Mat2]): s.Repr[F[Out]] =
        s.alsoTo(Flow[F[Out]].flattenF.to(that))
    }

    trait IterableOnceOpsImplicits {

      implicit class SourceIterableOnceOps[Out, Mat, F[Out] <: IterableOnce[Out]](
                                                                                   val s: Source[F[Out], Mat]
                                                                                 ) extends IterableOnceOps[Out, Mat, F]

      implicit class FlowIterableOnceOps[In, Out, Mat, F[Out] <: IterableOnce[Out]](
                                                                                     val s: Flow[In, F[Out], Mat]
                                                                                   ) extends IterableOnceOps[Out, Mat, F]

      implicit class SubFlowIterableOnceOps[Out, Mat, SubFlowF[+_], C, F[Out] <: IterableOnce[Out]](
                                                                                                     val s: SubFlow[F[Out], Mat, SubFlowF, C]
                                                                                                   )(
                                                                                                     implicit val traverseFilter: TraverseFilter[F]
                                                                                                   ) extends IterableOnceOps[Out, Mat, F]
    }

    trait OptionOps[Out, Mat] {
      val s: FlowOps[Option[Out], Mat]

      def groupByF[K](maxSubstreams: Int, f: Out => K): SubFlow[Option[Out], Mat, s.Repr, s.Closed] = {
        s.groupBy(maxSubstreams, _.map(f))
      }

      /*
      this produces a Some(zero) even if there are no Some(...) in the stream, if you want to skip those use foldS
       */
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

      def foldS[Out2](zero: Out2)(f: (Out2, Out) => Out2): s.Repr[Option[Out2]] =
        foldF(zero)(f).filterNot(_.contains(zero))

      def mapConcatF[Out2](f: Out => IterableOnce[Out2]): s.Repr[Option[Out2]] = s.mapConcat {
        case Some(value) => f(value).iterator.map(Some(_))
        case None => Seq(None)
      }

      def viaF[Out2](f: Flow[Out, Out2, Mat]): s.Repr[Option[Out2]] =
        s.via(Flow
          .fromGraph(GraphDSL.create() { implicit builder =>
            import GraphDSL.Implicits._

            val broadcast = builder.add(Broadcast[Option[Out]](2))

            val remapNone = builder.add(Flow[Option[Out]].collect {
              case None => None // remapping necessary due to the changed Option type
            })

            val collectSome = builder.add(Flow[Option[Out]].flattenF)
            val viaFlow = builder.add(f)
            val mapSome = builder.add(Flow[Out2].map(_.some))

            val merger = builder.add(Merge[Option[Out2]](2))

            broadcast.out(0) ~> remapNone ~> merger.in(0)
            broadcast.out(1) ~> collectSome ~> viaFlow ~> mapSome ~> merger.in(1)

            FlowShape(broadcast.in, merger.out)
          }))
    }

    trait OptionOpsImplicits {
      implicit class SourceOptionOps[Out, Mat](
                                                val s: Source[Option[Out], Mat]
                                              ) extends OptionOps[Out, Mat]

      implicit class FlowOptionOps[In, Out, Mat](
                                                  val s: Flow[In, Option[Out], Mat]
                                                ) extends OptionOps[Out, Mat]

      implicit class SubFlowOptionOps[Out, Mat, SubFlowF[+_], C](
                                                                  val s: SubFlow[Option[Out], Mat, SubFlowF, C]
                                                                ) extends OptionOps[Out, Mat]
    }

    trait EitherOps[OutL, OutR, Mat] {
      val s: FlowOps[Either[OutL, OutR], Mat]

      def filterOrElseF(p: OutR => Boolean, zero: => OutL): s.Repr[Either[OutL, OutR]] = s.map(_.filterOrElse(p, zero))

      def collectF[OutR2](pf: PartialFunction[OutR, OutR2])(zero: => OutL): s.Repr[Either[OutL, OutR2]] = s.map(_.flatMap {
        case right if pf.isDefinedAt(right) => Right(pf(right))
        case _ => Left(zero)
      })

      def collectLeftF: s.Repr[OutL] = s.collect {
        case Left(value) => value
      }

      def collectRightF: s.Repr[OutR] = s.collect {
        case Right(value) => value
      }

      def flattenF[OutR2](implicit ev: OutR <:< Either[OutL, OutR2]): s.Repr[Either[OutL, OutR2]] =
        s.map {
          case Left(outL) => Left(outL)
          case Right(outR) => ev.apply(outR)
        }

      def groupByF[K](maxSubstreams: Int, f: OutR => K): SubFlow[Either[OutL, OutR], Mat, s.Repr, s.Closed] = {
        s.groupBy(maxSubstreams, {
          case Right(value) => Some(f(value))
          case _ => None
        })
      }

      /*
      this produces a Right(zero) even if there are no Right(...) in the stream, if you want to skip those use foldS
       */
      def foldF[OutR2](zero: OutR2)(f: (OutR2, OutR) => OutR2): s.Repr[Either[OutL, OutR2]] =
        s.fold(
          zero -> Seq.empty[Left[OutL, OutR2]]
        ) {
          case ((acc, lefts), next) => next match {
            case Right(value) => f(acc, value) -> lefts
            case Left(value) => acc -> (lefts :+ Left(value))
          }
        }.mapConcat { case (value, lefts) =>
          lefts :+ Right(value)
        }

      def foldS[OutR2](zero: OutR2)(f: (OutR2, OutR) => OutR2): s.Repr[Either[OutL, OutR2]] =
        foldF(zero)(f).filterNot(_.contains(zero))

      def mapConcatF[OutR2](f: OutR => IterableOnce[OutR2]): s.Repr[Either[OutL, OutR2]] = s.mapConcat {
        case Right(value) => f(value).iterator.map(Right(_))
        case Left(value) => Seq(Left(value))
      }

      def viaF[OutR2](f: Flow[OutR, OutR2, Mat]): s.Repr[Either[OutL, OutR2]] =
        s.via(Flow
          .fromGraph(GraphDSL.create() { implicit builder =>
            import GraphDSL.Implicits._

            val broadcast = builder.add(Broadcast[Either[OutL, OutR]](2))

            val remapLeft = builder.add(Flow[Either[OutL, OutR]].collect {
              case Left(value) => value.asLeft // remapping necessary due to the changed Right type
            })

            val collectRight = builder.add(Flow[Either[OutL, OutR]].collectRightF)
            val viaFlow = builder.add(f)
            val mapRight = builder.add(Flow[OutR2].map(_.asRight))

            val merger = builder.add(Merge[Either[OutL, OutR2]](2))

            broadcast.out(0) ~> remapLeft ~> merger.in(0)
            broadcast.out(1) ~> collectRight ~> viaFlow ~> mapRight ~> merger.in(1)

            FlowShape(broadcast.in, merger.out)
          }))

      def alsoToF[Mat2](that: akka.stream.Graph[akka.stream.SinkShape[OutR], Mat2]): s.Repr[Either[OutL, OutR]] =
        s.alsoTo(Flow[Either[OutL, OutR]].collectRightF.to(that))
    }

    trait EitherOpsImplicits {
      implicit class SourceEitherOps[OutL, OutR, Mat](
                                                       val s: Source[Either[OutL, OutR], Mat]
                                                     ) extends EitherOps[OutL, OutR, Mat]

      implicit class FlowEitherOps[In, OutL, OutR, Mat](
                                                         val s: Flow[In, Either[OutL, OutR], Mat]
                                                       ) extends EitherOps[OutL, OutR, Mat]

      implicit class SubFlowEitherOps[OutL, OutR, Mat, SubFlowF[+_], C, F[_]](
                                                                               val s: SubFlow[Either[OutL, OutR], Mat, SubFlowF, C]
                                                                             ) extends EitherOps[OutL, OutR, Mat]
    }

    trait TryOps[Out, Mat] {
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

      /*
      this produces a Success(zero) even if there are no Success(...) in the stream, if you want to skip those use foldS
       */
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

      def foldS[Out2](zero: Out2)(f: (Out2, Out) => Out2): s.Repr[Try[Out2]] =
        foldF(zero)(f).filterNot(_ == Success(zero))

      def mapConcatF[Out2](f: Out => IterableOnce[Out2]): s.Repr[Try[Out2]] = s.mapConcat {
        case Failure(exception) => Seq(Failure(exception))
        case Success(value) => f(value).iterator.map(Success(_))
      }

      def alsoToF[Mat2](that: akka.stream.Graph[akka.stream.SinkShape[Out], Mat2]): s.Repr[Try[Out]] =
        s.alsoTo(Flow[Try[Out]].collect { case Success(value) => value }.to(that))

      def viaF[Out2](f: Flow[Out, Out2, Mat]): s.Repr[Try[Out2]] =
        s.via(Flow
          .fromGraph(GraphDSL.create() { implicit builder =>
            import GraphDSL.Implicits._

            val broadcast = builder.add(Broadcast[Try[Out]](2))

            val remapFailure = builder.add(Flow[Try[Out]].collect {
              case Failure(exception) => Failure(exception) // remapping necessary due to the changed Try type
            })

            val collectSuccess = builder.add(Flow[Try[Out]].collect {
              case Success(value) => value
            })
            val viaFlow = builder.add(f)
            val mapSuccess = builder.add(Flow[Out2].map(Success(_)))

            val merger = builder.add(Merge[Try[Out2]](2))

            broadcast.out(0) ~> remapFailure ~> merger.in(0)
            broadcast.out(1) ~> collectSuccess ~> viaFlow ~> mapSuccess ~> merger.in(1)

            FlowShape(broadcast.in, merger.out)
          }))
    }

    trait TryOpsImplicits {

      implicit class SourceTryOps[Out, Mat](
                                             val s: Source[Try[Out], Mat]
                                           ) extends TryOps[Out, Mat]

      implicit class FlowTryOps[In, Out, Mat](
                                               val s: Flow[In, Try[Out], Mat]
                                             ) extends TryOps[Out, Mat]

      implicit class SubFlowTryOps[Out, Mat, SubFlowF[+_], C, F[_]](
                                                                     val s: SubFlow[Try[Out], Mat, SubFlowF, C]
                                                                   ) extends TryOps[Out, Mat]
    }

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

    trait AsyncOpsImplicits {

      implicit class AsyncSourceOps[Out, Mat](
                                               val s: Source[Out, Mat]
                                             ) extends AsyncOps[Out, Mat]

      implicit class AsyncFlowOps[In, Out, Mat](
                                                 val s: Flow[In, Out, Mat]
                                               ) extends AsyncOps[Out, Mat]

      implicit class AsyncSubFlowOps[Out, Mat, SubFlowF[+_], C](
                                                                 val s: SubFlow[Out, Mat, SubFlowF, C]
                                                               ) extends AsyncOps[Out, Mat]
    }

    trait WrapperOps[Out, Mat, F[_]] {
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

      //def groupByF[K](maxSubstreams: Int, f: Out => K): SubFlow[F[Out], Mat, s.Repr, s.Closed] = {
      //  s.groupBy(maxSubstreams, _.map(f))
      //}
      //
      //def groupByF[K](maxSubstreams: Int, f: Out => K): SubFlow[F[Out], Mat, s.Repr, s.Closed] = {
      //  s.groupBy(maxSubstreams, {
      //    case Success(value) => Some(f(value))
      //    case _ => None
      //  })
      //}
    }

    trait WrapperOpsImplicits {

      implicit class SourceWrapperOps[Out, Mat, F[_]](
                                                       val s: Source[F[Out], Mat]
                                                     )(
                                                       implicit val wrapper: Wrapper[F]
                                                     ) extends WrapperOps[Out, Mat, F]

      implicit class FlowWrapperOps[In, Out, Mat, F[_]](
                                                         val s: Flow[In, F[Out], Mat]
                                                       )(
                                                         implicit val wrapper: Wrapper[F]
                                                       ) extends WrapperOps[Out, Mat, F]

      implicit class SubFlowWrapperOps[Out, Mat, SubFlowF[+_], C, F[_]](
                                                                         val s: SubFlow[F[Out], Mat, SubFlowF, C]
                                                                       )(
                                                                         implicit val wrapper: Wrapper[F]
                                                                       ) extends WrapperOps[Out, Mat, F]
    }

    trait Wrapper2Ops[OutA, OutB, Mat, F[_, _]] {
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

    trait Wrapper2OpsImplicits {
      implicit class SourceWrapper2Ops[OutA, OutB, Mat, F[_, _]](
                                                                  val s: Source[F[OutA, OutB], Mat]
                                                                )(
                                                                  implicit val wrapper: Wrapper2[F]
                                                                ) extends Wrapper2Ops[OutA, OutB, Mat, F]

      implicit class FlowWrapper2Ops[In, OutA, OutB, Mat, F[_, _]](
                                                                    val s: Flow[In, F[OutA, OutB], Mat]
                                                                  )(
                                                                    implicit val wrapper: Wrapper2[F]
                                                                  ) extends Wrapper2Ops[OutA, OutB, Mat, F]

      implicit class SubFlowWrapper2Ops[OutA, OutB, Mat, SubFlowF[+_], C, F[_, _]](
                                                                                    val s: SubFlow[F[OutA, OutB], Mat, SubFlowF, C]
                                                                                  )(
                                                                                    implicit val wrapper: Wrapper2[F]
                                                                                  ) extends Wrapper2Ops[OutA, OutB, Mat, F]
    }
  }

  // groupBy, fold and mapAsyncUnordered*, flatMapConcat, flatMapConcatMerge not possible due to https://doc.akka.io/docs/akka/current/stream/stream-context.html#restrictions , hence SubFlow not available either
  object sourceWithContext {
    trait MonadOps[Out, Ctx, Mat, F[_]] {

      val s: FlowWithContextOps[F[Out], Ctx, Mat]
      val monad: Monad[F]

      def mapF[Out2](f: Out => Out2): s.Repr[F[Out2], Ctx] = s.map(monad.lift(f))

      def flatMapF[Out2](f: Out => F[Out2]): s.Repr[F[Out2], Ctx] = s.map(monad.flatMap(_)(f))
    }

    trait MonadOpsImplicits {
      implicit class SourceMonadOps[Out, Ctx, Mat, F[_]](
                                                          val s: SourceWithContext[F[Out], Ctx, Mat]
                                                        )(
                                                          implicit val monad: Monad[F]
                                                        ) extends MonadOps[Out, Ctx, Mat, F]

      implicit class FlowMonadOps[In, CtxIn, Out, CtxOut, Mat, F[_]](
                                                                      val s: FlowWithContext[In, CtxIn, F[Out], CtxOut, Mat]
                                                                    )(
                                                                      implicit val monad: Monad[F]
                                                                    ) extends MonadOps[Out, CtxOut, Mat, F]
    }

    trait FilterOps[Out, Ctx, Mat, F[_]] {
      val s: FlowWithContextOps[F[Out], Ctx, Mat]
      val traverseFilter: TraverseFilter[F]

      def filterF(p: Out => Boolean): s.Repr[F[Out], Ctx] = s.map(traverseFilter.filter(_)(p))

      def collectF[Out2](pf: PartialFunction[Out, Out2]): s.Repr[F[Out2], Ctx] = s.map(traverseFilter.collect(_)(pf))
    }

    trait FilterOpsImplicits {

      implicit class SourceFilterOps[Out, Ctx, Mat, F[_]](
                                                           val s: SourceWithContext[F[Out], Ctx, Mat]
                                                         )(
                                                           implicit val traverseFilter: TraverseFilter[F]
                                                         ) extends FilterOps[Out, Ctx, Mat, F]

      implicit class FlowFilterOps[In, CtxIn, Out, CtxOut, Mat, F[_]](
                                                                       val s: FlowWithContext[In, CtxIn, F[Out], CtxOut, Mat]
                                                                     )(
                                                                       implicit val traverseFilter: TraverseFilter[F]
                                                                     ) extends FilterOps[Out, CtxOut, Mat, F]
    }

    trait IterableOnceOps[Out, Ctx, Mat, F[Out] <: IterableOnce[Out]] {
      val s: FlowWithContextOps[F[Out], Ctx, Mat]

      def flattenF: s.Repr[Out, Ctx] = s.mapConcat(identity)
    }

    trait IterableOnceOpsImplicits {

      implicit class SourceIterableOnceOps[Out, Ctx, Mat, F[Out] <: IterableOnce[Out]](
                                                                                        val s: SourceWithContext[F[Out], Ctx, Mat]
                                                                                      ) extends IterableOnceOps[Out, Ctx, Mat, F]

      implicit class FlowIterableOnceOps[In, CtxIn, Out, CtxOut, Mat, F[Out] <: IterableOnce[Out]](
                                                                                                    val s: FlowWithContext[In, CtxIn, F[Out], CtxOut, Mat]
                                                                                                  ) extends IterableOnceOps[Out, CtxOut, Mat, F]
    }

    trait OptionOps[Out, Ctx, Mat] {
      val s: FlowWithContextOps[Option[Out], Ctx, Mat]

      def mapConcatF[Out2](f: Out => IterableOnce[Out2]): s.Repr[Option[Out2], Ctx] = s.mapConcat {
        case Some(value) => f(value).iterator.map(Some(_))
        case None => Seq(None)
      }
    }

    trait OptionOpsImplicits {

      implicit class SourceOptionOps[Out, Ctx, Mat](
                                                     val s: SourceWithContext[Option[Out], Ctx, Mat]
                                                   ) extends OptionOps[Out, Ctx, Mat]

      implicit class FlowOptionOps[In, CtxIn, Out, CtxOut, Mat](
                                                                 val s: FlowWithContext[In, CtxIn, Option[Out], CtxOut, Mat]
                                                               ) extends OptionOps[Out, CtxOut, Mat]
    }

    trait EitherOps[OutL, OutR, Ctx, Mat] {
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

    trait EitherOpsImplicits {
      implicit class SourceEitherOps[OutL, OutR, Ctx, Mat](
                                                            val s: SourceWithContext[Either[OutL, OutR], Ctx, Mat]
                                                          ) extends EitherOps[OutL, OutR, Ctx, Mat]

      implicit class FlowEitherOps[In, CtxIn, OutL, OutR, CtxOut, Mat](
                                                                        val s: FlowWithContext[In, CtxIn, Either[OutL, OutR], CtxOut, Mat]
                                                                      ) extends EitherOps[OutL, OutR, CtxOut, Mat]
    }

    trait TryOps[Out, Ctx, Mat] {
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

    trait TryOpsImplicits {
      implicit class SourceTryOps[Out, Ctx, Mat](
                                                  val s: SourceWithContext[Try[Out], Ctx, Mat]
                                                ) extends TryOps[Out, Ctx, Mat]

      implicit class FlowTryOps[In, CtxIn, Out, CtxOut, Mat](
                                                              val s: FlowWithContext[In, CtxIn, Try[Out], CtxOut, Mat]
                                                            ) extends TryOps[Out, CtxOut, Mat]
    }

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

    trait AsyncOpsImplicits {
      implicit class AsyncSourceOps[Out, Ctx, Mat](
                                                    val s: SourceWithContext[Out, Ctx, Mat]
                                                  ) extends AsyncOps[Out, Ctx, Mat]

      implicit class AsyncFlowOps[In, CtxIn, Out, CtxOut, Mat](
                                                                val s: FlowWithContext[In, CtxIn, Out, CtxOut, Mat]
                                                              ) extends AsyncOps[Out, CtxOut, Mat]
    }

    trait WrapperOps[Out, Ctx, Mat, F[_]] {
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

    trait WrapperOpsImplicits {
      implicit class SourceWrapperOps[Out, Ctx, Mat, F[_]](
                                                            val s: SourceWithContext[F[Out], Ctx, Mat]
                                                          )(
                                                            implicit val wrapper: Wrapper[F]
                                                          ) extends WrapperOps[Out, Ctx, Mat, F]

      implicit class FlowWrapperOps[In, CtxIn, Out, CtxOut, Mat, F[_]](
                                                                        val s: FlowWithContext[In, CtxIn, F[Out], CtxOut, Mat]
                                                                      )(
                                                                        implicit val wrapper: Wrapper[F]
                                                                      ) extends WrapperOps[Out, CtxOut, Mat, F]
    }

    trait Wrapper2Ops[OutA, OutB, Ctx, Mat, F[_, _]] {
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

    trait Wrapper2OpsImplicits {
      implicit class SourceWrapper2Ops[OutA, OutB, Ctx, Mat, F[_, _]](
                                                                       val s: SourceWithContext[F[OutA, OutB], Ctx, Mat]
                                                                     )(
                                                                       implicit val wrapper: Wrapper2[F]
                                                                     ) extends Wrapper2Ops[OutA, OutB, Ctx, Mat, F]

      implicit class FlowWrapper2Ops[In, CtxIn, OutA, OutB, CtxOut, Mat, F[_, _]](
                                                                                   val s: FlowWithContext[In, CtxIn, F[OutA, OutB], CtxOut, Mat]
                                                                                 )(
                                                                                   implicit val wrapper: Wrapper2[F]
                                                                                 ) extends Wrapper2Ops[OutA, OutB, CtxOut, Mat, F]
    }
  }
}
