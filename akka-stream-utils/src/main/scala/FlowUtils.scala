package net.sc8s.akka.stream

import akka.stream.scaladsl.{Flow, FlowOps, FlowWithContext, FlowWithContextOps, Source, SourceWithContext}
import cats.instances.either._
import cats.instances.future._
import cats.instances.option._
import cats.instances.try_._
import cats.syntax.traverse._
import cats.{Monad, TraverseFilter}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object FlowUtils {
  trait Wrapper[F[_]] {

    def mapAsync[A, B](fa: F[A])(f: A => Future[B])(implicit executionContext: ExecutionContext): Future[F[B]]

    def filterS[A](fa: F[A])(p: A => Boolean): Boolean

    def collectS[A, B](pf: PartialFunction[A, B]): PartialFunction[F[A], F[B]]
  }

  trait Wrapper2[F[_, _]] {

    def mapAsync[A, B, C](fa: F[A, B])(f: B => Future[C])(implicit executionContext: ExecutionContext): Future[F[A, C]]

    def filterS[A, B](fa: F[A, B])(p: B => Boolean): Boolean

    def collectS[A, B, C](pf: PartialFunction[B, C]): PartialFunction[F[A, B], F[A, C]]
  }

  implicit object OptionWrapper extends Wrapper[Option] {

    override def mapAsync[A, B](fa: Option[A])(f: A => Future[B])(implicit executionContext: ExecutionContext) = fa.map(f).sequence

    override def filterS[A](fa: Option[A])(p: A => Boolean) = fa.isEmpty || fa.exists(p)

    override def collectS[A, B](pf: PartialFunction[A, B]) = {
      case None => None
      case Some(value) if pf.isDefinedAt(value) => Some(pf(value))
    }
  }

  implicit object EitherWrapper extends Wrapper2[Either] {

    override def mapAsync[L, R, T](fa: Either[L, R])(f: R => Future[T])(implicit executionContext: ExecutionContext) = fa.map(f).sequence

    override def filterS[L, R](fa: Either[L, R])(p: R => Boolean) = fa.isLeft || fa.exists(p)

    override def collectS[L, R, R2](pf: PartialFunction[R, R2]) = {
      case Left(l) => Left(l)
      case Right(right) if pf.isDefinedAt(right) => Right(pf(right))
    }
  }

  implicit object TryWrapper extends Wrapper[Try] {

    override def mapAsync[A, B](fa: Try[A])(f: A => Future[B])(implicit executionContext: ExecutionContext): Future[Try[B]] = fa.map(f).sequence

    override def filterS[A](fa: Try[A])(p: A => Boolean) = fa.isFailure || fa.toOption.exists(p)

    override def collectS[A, B](pf: PartialFunction[A, B]) = {
      case Failure(e) => Failure(e)
      case Success(value) if pf.isDefinedAt(value) => Success(pf(value))
    }
  }

  // This needs "duplication" https://doc.akka.io/docs/akka/current/stream/stream-customize.html#extending-flow-operators-with-custom-operators :(

  object source {
    implicit class SourceMonadOpsF[Out, Mat, F[_]](
                                                    val s: Source[F[Out], Mat]
                                                      with FlowOps[F[Out], Mat]
                                                  )(implicit monad: Monad[F]) {
      def mapF[Out2](f: Out => Out2): Source[F[Out2], Mat] = s.map(monad.map(_)(f))

      def flatMapF[Out2](f: Out => F[Out2]): Source[F[Out2], Mat] = s.map(monad.flatMap(_)(f))
    }

    implicit class SourceFilterOpsF[Out, Mat, F[_]](
                                                     val s: Source[F[Out], Mat]
                                                       with FlowOps[F[Out], Mat]
                                                   )(implicit traverseFilter: TraverseFilter[F]) {
      def filterF(p: Out => Boolean): Source[F[Out], Mat] = s.map(traverseFilter.filter(_)(p))

      def collectF[Out2](pf: PartialFunction[Out, Out2]): Source[F[Out2], Mat] = s.map(traverseFilter.collect(_)(pf))
    }

    implicit class SourceEitherFilterOpsF[OutL, OutR, Mat](
                                                            val s: Source[Either[OutL, OutR], Mat]
                                                              with FlowOps[Either[OutL, OutR], Mat]
                                                          ) {
      def filterOrElseF(p: OutR => Boolean, zero: => OutL): Source[Either[OutL, OutR], Mat] = s.map(_.filterOrElse(p, zero))

      def collectF[OutR2](pf: PartialFunction[OutR, OutR2])(zero: => OutL): Source[Either[OutL, OutR2], Mat] = s.map(_.flatMap {
        case right if pf.isDefinedAt(right) => Right(pf(right))
        case _ => Left(zero)
      })

      def flattenF: Source[OutR, Mat] = s.collect {
        case Right(value) => value
      }
    }

    implicit class SourceOptionFilterOpsF[Out, Mat](
                                                     val s: Source[Option[Out], Mat]
                                                       with FlowOps[Option[Out], Mat]
                                                   ) {
      def flattenF: Source[Out, Mat] = s.collect {
        case Some(value) => value
      }
    }

    implicit class SourceTryFilterOpsF[Out, Mat](
                                                  val s: Source[Try[Out], Mat]
                                                    with FlowOps[Try[Out], Mat]
                                                ) {
      def filterOrElseF(p: Out => Boolean, zero: => Throwable): Source[Try[Out], Mat] = s.map(_.flatMap {
        case value if p(value) => Success(value)
        case _ => Failure(zero)
      })

      def collectF[Out2](pf: PartialFunction[Out, Out2])(zero: => Throwable): Source[Try[Out2], Mat] = s.map(_.flatMap {
        case value if pf.isDefinedAt(value) => Success(pf(value))
        case _ => Failure(zero)
      })

      def flattenF: Source[Out, Mat] = s.collect {
        case Success(value) => value
      }
    }

    implicit class SourceOpsS[Out, Mat, F[_]](
                                               val s: Source[F[Out], Mat]
                                                 with FlowOps[F[Out], Mat]
                                             )(implicit wrapper: Wrapper[F]) {
      def mapAsyncS[Out2](parallelism: Int)(f: Out => Future[Out2])(implicit executionContext: ExecutionContext): Source[F[Out2], Mat] =
        s.mapAsync(parallelism)(wrapper.mapAsync(_)(f))

      def filterS[Out2](p: Out => Boolean): Source[F[Out], Mat] = s.filter(wrapper.filterS(_)(p))

      def collectS[Out2](pf: PartialFunction[Out, Out2]): Source[F[Out2], Mat] = s.collect(wrapper.collectS(pf))
    }

    implicit class SourceOpsS2[OutA, OutB, Mat, F[_, _]](
                                                          val s: Source[F[OutA, OutB], Mat]
                                                            with FlowOps[F[OutA, OutB], Mat]
                                                        )(implicit wrapper: Wrapper2[F]) {
      def mapAsyncS[Out2](parallelism: Int)(f: OutB => Future[Out2])(implicit executionContext: ExecutionContext) =
        s.mapAsync(parallelism)(wrapper.mapAsync(_)(f))

      def filterS[Out2](p: OutB => Boolean) = s.filter(wrapper.filterS(_)(p))

      def collectS[Out2](pf: PartialFunction[OutB, Out2]) = s.collect(wrapper.collectS(pf))
    }
  }

  object sourceWithContext {
    implicit class SourceMonadOpsF[Out, Ctx, Mat, F[_]](
                                                         val s: SourceWithContext[F[Out], Ctx, Mat]
                                                           with FlowWithContextOps[F[Out], Ctx, Mat]
                                                       )(implicit monad: Monad[F]) {
      def mapF[Out2](f: Out => Out2): SourceWithContext[F[Out2], Ctx, Mat] = s.map(monad.map(_)(f))

      def flatMapF[Out2](f: Out => F[Out2]): SourceWithContext[F[Out2], Ctx, Mat] = s.map(monad.flatMap(_)(f))
    }

    implicit class SourceFilterOpsF[Out, Ctx, Mat, F[_]](
                                                          val s: SourceWithContext[F[Out], Ctx, Mat]
                                                            with FlowWithContextOps[F[Out], Ctx, Mat]
                                                        )(implicit traverseFilter: TraverseFilter[F]) {
      def filterF(p: Out => Boolean): SourceWithContext[F[Out], Ctx, Mat] = s.map(traverseFilter.filter(_)(p))

      def collectF[Out2](pf: PartialFunction[Out, Out2]): SourceWithContext[F[Out2], Ctx, Mat] = s.map(traverseFilter.collect(_)(pf))
    }

    implicit class SourceEitherFilterOpsF[OutL, OutR, Ctx, Mat](
                                                                 val s: SourceWithContext[Either[OutL, OutR], Ctx, Mat]
                                                                   with FlowWithContextOps[Either[OutL, OutR], Ctx, Mat]
                                                               ) {
      def filterOrElseF(p: OutR => Boolean, zero: => OutL): SourceWithContext[Either[OutL, OutR], Ctx, Mat] = s.map(_.filterOrElse(p, zero))

      def collectF[OutR2](pf: PartialFunction[OutR, OutR2])(zero: => OutL): SourceWithContext[Either[OutL, OutR2], Ctx, Mat] = s.map(_.flatMap {
        case right if pf.isDefinedAt(right) => Right(pf(right))
        case _ => Left(zero)
      })
    }

    implicit class SourceTryFilterOpsF[Out, Ctx, Mat](
                                                       val s: SourceWithContext[Try[Out], Ctx, Mat]
                                                         with FlowWithContextOps[Try[Out], Ctx, Mat]
                                                     ) {
      def filterOrElseF(p: Out => Boolean, zero: => Throwable): SourceWithContext[Try[Out], Ctx, Mat] = s.map(_.flatMap {
        case value if p(value) => Success(value)
        case _ => Failure(zero)
      })

      def collectF[Out2](pf: PartialFunction[Out, Out2])(zero: => Throwable): SourceWithContext[Try[Out2], Ctx, Mat] = s.map(_.flatMap {
        case value if pf.isDefinedAt(value) => Success(pf(value))
        case _ => Failure(zero)
      })
    }

    implicit class SourceOpsS[Out, Ctx, Mat, F[_]](
                                                    val s: SourceWithContext[F[Out], Ctx, Mat]
                                                      with FlowWithContextOps[F[Out], Ctx, Mat]
                                                  )(implicit wrapper: Wrapper[F]) {
      def mapAsyncS[Out2](parallelism: Int)(f: Out => Future[Out2])(implicit executionContext: ExecutionContext): SourceWithContext[F[Out2], Ctx, Mat] =
        s.mapAsync(parallelism)(wrapper.mapAsync(_)(f))

      def filterS[Out2](p: Out => Boolean): SourceWithContext[F[Out], Ctx, Mat] = s.filter(wrapper.filterS(_)(p))

      def collectS[Out2](pf: PartialFunction[Out, Out2]): SourceWithContext[F[Out2], Ctx, Mat] = s.collect(wrapper.collectS(pf))
    }

    implicit class SourceOpsS2[OutA, OutB, Ctx, Mat, F[_, _]](
                                                               val s: SourceWithContext[F[OutA, OutB], Ctx, Mat]
                                                                 with FlowWithContextOps[F[OutA, OutB], Ctx, Mat]
                                                             )(implicit wrapper: Wrapper2[F]) {
      def mapAsyncS[OutB2](parallelism: Int)(f: OutB => Future[OutB2])(implicit executionContext: ExecutionContext): SourceWithContext[F[OutA, OutB2], Ctx, Mat] =
        s.mapAsync(parallelism)(wrapper.mapAsync(_)(f))

      def filterS[Out2](p: OutB => Boolean): SourceWithContext[F[OutA, OutB], Ctx, Mat] = s.filter(wrapper.filterS(_)(p))

      def collectS[OutB2](pf: PartialFunction[OutB, OutB2]): SourceWithContext[F[OutA, OutB2], Ctx, Mat] = s.collect(wrapper.collectS(pf))
    }
  }

  object flow {
    implicit class FlowMonadOpsF[In, Out, Mat, F[_]](
                                                      val s: Flow[In, F[Out], Mat]
                                                        with FlowOps[F[Out], Mat]
                                                    )(implicit monad: Monad[F]) {
      def mapF[Out2](f: Out => Out2): Flow[In, F[Out2], Mat] = s.map(monad.map(_)(f))

      def flatMapF[Out2](f: Out => F[Out2]): Flow[In, F[Out2], Mat] = s.map(monad.flatMap(_)(f))
    }

    implicit class FlowFilterOpsF[In, Out, Mat, F[_]](
                                                       val s: Flow[In, F[Out], Mat]
                                                         with FlowOps[F[Out], Mat]
                                                     )(implicit traverseFilter: TraverseFilter[F]) {
      def filterF(p: Out => Boolean): Flow[In, F[Out], Mat] = s.map(traverseFilter.filter(_)(p))

      def collectF[Out2](pf: PartialFunction[Out, Out2]): Flow[In, F[Out2], Mat] = s.map(traverseFilter.collect(_)(pf))
    }

    implicit class FlowEitherFilterOpsF[In, OutL, OutR, Mat](
                                                              val s: Flow[In, Either[OutL, OutR], Mat]
                                                                with FlowOps[Either[OutL, OutR], Mat]
                                                            ) {
      def filterOrElseF(p: OutR => Boolean, zero: => OutL): Flow[In, Either[OutL, OutR], Mat] = s.map(_.filterOrElse(p, zero))

      def collectF[Out2](pf: PartialFunction[OutR, Out2])(zero: => OutL): Flow[In, Either[OutL, Out2], Mat] = s.map(_.flatMap {
        case right if pf.isDefinedAt(right) => Right(pf(right))
        case _ => Left(zero)
      })
    }

    implicit class FlowTryFilterOpsF[In, Out, Mat](
                                                    val s: Flow[In, Try[Out], Mat]
                                                      with FlowOps[Try[Out], Mat]
                                                  ) {
      def filterOrElseF(p: Out => Boolean, zero: => Throwable): Flow[In, Try[Out], Mat] = s.map(_.flatMap {
        case value if p(value) => Success(value)
        case _ => Failure(zero)
      })

      def collectF[Out2](pf: PartialFunction[Out, Out2])(zero: => Throwable): Flow[In, Try[Out2], Mat] = s.map(_.flatMap {
        case value if pf.isDefinedAt(value) => Success(pf(value))
        case _ => Failure(zero)
      })
    }

    implicit class FlowOpsS[In, Out, Mat, F[_]](
                                                 val s: Flow[In, F[Out], Mat]
                                                   with FlowOps[F[Out], Mat]
                                               )(implicit wrapper: Wrapper[F]) {
      def mapAsyncS[Out2](parallelism: Int)(f: Out => Future[Out2])(implicit executionContext: ExecutionContext): Flow[In, F[Out2], Mat] =
        s.mapAsync(parallelism)(wrapper.mapAsync(_)(f))

      def filterS[Out2](p: Out => Boolean): Flow[In, F[Out], Mat] = s.filter(wrapper.filterS(_)(p))

      def collectS[Out2](pf: PartialFunction[Out, Out2]): Flow[In, F[Out2], Mat] = s.collect(wrapper.collectS(pf))
    }

    implicit class FlowOpsS2[In, OutA, OutB, Mat, F[_, _]](
                                                            val s: Flow[In, F[OutA, OutB], Mat]
                                                              with FlowOps[F[OutA, OutB], Mat]
                                                          )(implicit wrapper: Wrapper2[F]) {
      def mapAsyncS[Out2](parallelism: Int)(f: OutB => Future[Out2])(implicit executionContext: ExecutionContext): Flow[In, F[OutA, Out2], Mat] =
        s.mapAsync(parallelism)(wrapper.mapAsync(_)(f))

      def filterS[Out2](p: OutB => Boolean): Flow[In, F[OutA, OutB], Mat] = s.filter(wrapper.filterS(_)(p))

      def collectS[Out2](pf: PartialFunction[OutB, Out2]): Flow[In, F[OutA, Out2], Mat] = s.collect(wrapper.collectS(pf))
    }
  }

  object flowWithContext {
    implicit class FlowWithContextMonadOpsF[In, CtxIn, Out, CtxOut, Mat, F[_]](
                                                                                val s: FlowWithContext[In, CtxIn, F[Out], CtxOut, Mat]
                                                                                  with FlowWithContextOps[F[Out], CtxOut, Mat]
                                                                              )(implicit monad: Monad[F]) {
      def mapF[Out2](f: Out => Out2): FlowWithContext[In, CtxIn, F[Out2], CtxOut, Mat] = s.map(monad.map(_)(f))

      def flatMapF[Out2](f: Out => F[Out2]): FlowWithContext[In, CtxIn, F[Out2], CtxOut, Mat] = s.map(monad.flatMap(_)(f))
    }

    implicit class FlowWithContextFilterOpsF[In, CtxIn, Out, CtxOut, Mat, F[_]](
                                                                                 val s: FlowWithContext[In, CtxIn, F[Out], CtxOut, Mat]
                                                                                   with FlowWithContextOps[F[Out], CtxOut, Mat]
                                                                               )(implicit traverseFilter: TraverseFilter[F]) {
      def filterF(p: Out => Boolean): FlowWithContext[In, CtxIn, F[Out], CtxOut, Mat] = s.map(traverseFilter.filter(_)(p))

      def collectF[Out2](pf: PartialFunction[Out, Out2]): FlowWithContext[In, CtxIn, F[Out2], CtxOut, Mat] = s.map(traverseFilter.collect(_)(pf))
    }

    implicit class FlowWithContextEitherFilterOpsF[In, CtxIn, OutL, OutR, CtxOut, Mat](
                                                                                        val s: FlowWithContext[In, CtxIn, Either[OutL, OutR], CtxOut, Mat]
                                                                                          with FlowWithContextOps[Either[OutL, OutR], CtxOut, Mat]
                                                                                      ) {
      def filterOrElseF(p: OutR => Boolean, zero: => OutL): FlowWithContext[In, CtxIn, Either[OutL, OutR], CtxOut, Mat] = s.map(_.filterOrElse(p, zero))

      def collectF[Out2](pf: PartialFunction[OutR, Out2])(zero: => OutL): FlowWithContext[In, CtxIn, Either[OutL, Out2], CtxOut, Mat] = s.map(_.flatMap {
        case right if pf.isDefinedAt(right) => Right(pf(right))
        case _ => Left(zero)
      })
    }

    implicit class FlowWithContextTryFilterOpsF[In, CtxIn, Out, CtxOut, Mat](
                                                                              val s: FlowWithContext[In, CtxIn, Try[Out], CtxOut, Mat]
                                                                                with FlowWithContextOps[Try[Out], CtxOut, Mat]
                                                                            ) {
      def filterOrElseF(p: Out => Boolean, zero: => Throwable): FlowWithContext[In, CtxIn, Try[Out], CtxOut, Mat] = s.map(_.flatMap {
        case value if p(value) => Success(value)
        case _ => Failure(zero)
      })

      def collectF[Out2](pf: PartialFunction[Out, Out2])(zero: => Throwable): FlowWithContext[In, CtxIn, Try[Out2], CtxOut, Mat] = s.map(_.flatMap {
        case value if pf.isDefinedAt(value) => Success(pf(value))
        case _ => Failure(zero)
      })
    }

    implicit class FlowWithContextOpsS[In, CtxIn, Out, CtxOut, Mat, F[_]](
                                                                           val s: FlowWithContext[In, CtxIn, F[Out], CtxOut, Mat]
                                                                             with FlowWithContextOps[F[Out], CtxOut, Mat]
                                                                         )(implicit wrapper: Wrapper[F]) {
      def mapAsyncS[Out2](parallelism: Int)(f: Out => Future[Out2])(implicit executionContext: ExecutionContext): FlowWithContext[In, CtxIn, F[Out2], CtxOut, Mat] =
        s.mapAsync(parallelism)(wrapper.mapAsync(_)(f))

      def filterS[Out2](p: Out => Boolean): FlowWithContext[In, CtxIn, F[Out], CtxOut, Mat] = s.filter(wrapper.filterS(_)(p))

      def collectS[Out2](pf: PartialFunction[Out, Out2]): FlowWithContext[In, CtxIn, F[Out2], CtxOut, Mat] = s.collect(wrapper.collectS(pf))
    }

    implicit class FlowWithContextOpsS2[In, CtxIn, OutA, OutB, CtxOut, Mat, F[_, _]](
                                                                                      val s: FlowWithContext[In, CtxIn, F[OutA, OutB], CtxOut, Mat]
                                                                                        with FlowWithContextOps[F[OutA, OutB], CtxOut, Mat]
                                                                                    )(implicit wrapper: Wrapper2[F]) {
      def mapAsyncS[Out2](parallelism: Int)(f: OutB => Future[Out2])(implicit executionContext: ExecutionContext): FlowWithContext[In, CtxIn, F[OutA, Out2], CtxOut, Mat] =
        s.mapAsync(parallelism)(wrapper.mapAsync(_)(f))

      def filterS[Out2](p: OutB => Boolean): FlowWithContext[In, CtxIn, F[OutA, OutB], CtxOut, Mat] = s.filter(wrapper.filterS(_)(p))

      def collectS[Out2](pf: PartialFunction[OutB, Out2]): FlowWithContext[In, CtxIn, F[OutA, Out2], CtxOut, Mat] = s.collect(wrapper.collectS(pf))
    }
  }
}
