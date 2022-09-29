package net.sc8s.akka.stream

import akka.NotUsed
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.stream.scaladsl.{Flow, Sink, Source}
import cats.implicits.{catsStdInstancesForEither, catsStdInstancesForOption, catsStdInstancesForTry, catsStdTraverseFilterForOption}
import izumi.logstage.api.IzLogger
import izumi.logstage.api.Log.Level
import izumi.logstage.sink.ConsoleSink.SimpleConsoleSink
import net.sc8s.akka.stream.FlowUtils.flow.{FlowMonadOpsF, FlowOptionOpsF}
import net.sc8s.akka.stream.FlowUtils.source._
import org.scalatest.prop.{TableDrivenPropertyChecks, TableFor2}
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class FlowUtilsSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with TableDrivenPropertyChecks {
  implicit val executionContext = testKit.system.executionContext

  implicit val consoleLogger = IzLogger(Level.Trace, SimpleConsoleSink)

  "FlowUtils" should {
    "Seq" in {
      val input = Seq(Seq(1, 2), Nil, Seq(3))

      val operations = Table[
        Source[Seq[Int], NotUsed] => Source[Seq[_], NotUsed],
        Seq[Seq[_]],
      ](
        (
          "Operation",
          "Results",
        ), (
          _.mapF(_ * 2),
          Seq(Seq(2, 4), Nil, Seq(6))
        ), (
          _.flatMapF(element => Seq(element * 2).filter(_ == 6)),
          Seq(Nil, Nil, Seq(6))
        ), (
          _.filterF(_ == 2),
          Seq(Seq(2), Nil, Nil)
        ), (
          _.collectF {
            case 2 => "moin"
          },
          Seq(Seq("moin"), Nil, Nil)
        )
      )

      checkTable[Int, Seq](input, operations)
    }
    "Seq flattenF" in {
      Source(Seq(Seq(1), Nil, Seq(2)))
        .flattenF
        .runWith(Sink.seq)
        .futureValue shouldBe Seq(1, 2)
    }
    "Option" in {
      val input = Seq(Some(1), None, Some(2))

      val operations = Table[
        Source[Option[Int], NotUsed] => Source[Option[_], NotUsed],
        Seq[Option[_]],
      ](
        (
          "Operation",
          "Results",
        ), (
          _.mapF(_ * 2),
          Seq(Some(2), None, Some(4))
        ), (
          _.flatMapF(element => Some(element * 2).filter(_ == 4)),
          Seq(None, None, Some(4))
        ), (
          _.flatMapConcatF(element => Source(Seq(element * 2, element * 4))),
          Seq(Some(2), Some(4), None, Some(4), Some(8))
        ), (
          _.mapAsyncF(1)(element => Future.successful(element * 2)),
          Seq(Some(2), None, Some(4))
        ), (
          _.mapAsyncRetryWithBackoffF(1)(element => Future.successful(element * 2)),
          Seq(Some(2), None, Some(4))
        ), (
          _.mapAsyncUnorderedF(1)(element => Future.successful(element * 2)),
          Seq(Some(2), None, Some(4))
        ), (
          _.mapAsyncUnorderedF(1)(element => Future.successful(element * 2)),
          Seq(Some(2), None, Some(4))
        ), (
          _.filterF(_ > 1),
          Seq(None, None, Some(2))
        ), (
          _.filterS(_ > 1),
          Seq(None, Some(2))
        ), (
          _.collectF {
            case 2 => "moin"
          },
          Seq(None, None, Some("moin"))
        ), (
          _.collectS {
            case 2 => "moin"
          },
          Seq(None, Some("moin"))
        )
      )

      checkTable[Int, Option](input, operations)
    }
    "Option flattenF" in {
      Source(Seq(Some(1), None, Some(2)))
        .flattenF
        .runWith(Sink.seq)
        .futureValue shouldBe Seq(1, 2)
    }
    "Option flatMapMergeF" in {
      Source(Seq(Some(1), None, Some(2)))
        .flatMapMergeF(8, i => Source(Seq(i * 2, i * 4)))
        .runWith(Sink.seq)
        .futureValue should contain theSameElementsAs Seq(Some(2), Some(4), None, Some(4), Some(8))
    }
    "Either" in {
      val input = Seq(Right(1), Left(true), Right(2))

      val operations = Table[
        Source[Either[Boolean, Int], NotUsed] => Source[Either[_, _], NotUsed],
        Seq[Either[_, _]],
      ](
        (
          "Operation",
          "Output",
        ), (
          _.mapF(_ * 2),
          Seq(Right(2), Left(true), Right(4))

        ), (
          _.mapAsyncF(1)(element => Future.successful(element * 2)),
          Seq(Right(2), Left(true), Right(4))
        ), (
          _.mapAsyncRetryWithBackoffF(1)(element => Future.successful(element * 2)),
          Seq(Right(2), Left(true), Right(4))
        ), (
          _.mapAsyncUnorderedF(1)(element => Future.successful(element * 2)),
          Seq(Right(2), Left(true), Right(4))
        ), (
          _.mapAsyncUnorderedRetryWithBackoffF(1)(element => Future.successful(element * 2)),
          Seq(Right(2), Left(true), Right(4))
        ), (
          _.flatMapF(element => if (element == 2) Right(element * 2) else Left(false)),
          Seq(Left(false), Left(true), Right(4))
        ), (
          _.flatMapConcatF(element => Source(Seq(element * 2, element * 4))),
          Seq(Right(2), Right(4), Left(true), Right(4), Right(8))
        ), (
          _.filterOrElseF(_ > 1, false),
          Seq(Left(false), Left(true), Right(2))
        ), (
          _.filterS(_ > 1),
          Seq(Left(true), Right(2))
        ), (
          _.collectF {
            case 2 => "moin"
          }(false),
          Seq(Left(false), Left(true), Right("moin"))
        ), (
          _.collectS {
            case 2 => "moin"
          },
          Seq(Left(true), Right("moin"))
        )
      )

      checkTable2[Boolean, Int, Either](input, operations)
    }
    "Either flattenF" in {
      Source(Seq(Right(1), Left(true), Right(2)))
        .flattenF
        .runWith(Sink.seq)
        .futureValue shouldBe Seq(1, 2)
    }
    "Either flatMapMergeF" in {
      Source(Seq(Right(1), Left(true), Right(2)))
        .flatMapMergeF(8, element => Source(Seq(element * 2, element * 4)))
        .runWith(Sink.seq)
        .futureValue should contain theSameElementsAs Seq(Right(2), Right(4), Left(true), Right(4), Right(8))
    }
    "Try" in {
      val exception = new Exception
      val exception2 = new Exception
      val input: Seq[Try[Int]] = Seq(Success(1), Failure(exception), Success(2))

      val operations: TableFor2[Source[Try[Int], NotUsed] => Source[Try[_], NotUsed], Seq[Try[_]]] = Table[
        Source[Try[Int], NotUsed] => Source[Try[_], NotUsed],
        Seq[Try[_]],
      ](
        (
          "Operation",
          "Results",
        ), (
          _.mapF(_ * 2),
          Seq(Success(2), Failure(exception), Success(4))
        ), (
          _.mapAsyncF(1)(element => Future.successful(element * 2)),
          Seq(Success(2), Failure(exception), Success(4))
        ), (
          _.mapAsyncRetryWithBackoffF(1)(element => Future.successful(element * 2)),
          Seq(Success(2), Failure(exception), Success(4))
        ), (
          _.mapAsyncUnorderedF(1)(element => Future.successful(element * 2)),
          Seq(Success(2), Failure(exception), Success(4))
        ), (
          _.mapAsyncUnorderedRetryWithBackoffF(1)(element => Future.successful(element * 2)),
          Seq(Success(2), Failure(exception), Success(4))
        ), (
          _.flatMapF(element => if (element == 2) Success(element * 2) else Failure(exception2)),
          Seq(Failure(exception2), Failure(exception), Success(4))
        ), (
          _.flatMapConcatF(element => Source(Seq(element * 2, element * 4))),
          Seq(Success(2), Success(4), Failure(exception), Success(4), Success(8))
        ), (
          _.filterOrElseF(_ > 1, exception2),
          Seq(Failure(exception2), Failure(exception), Success(2))
        ), (
          _.filterS(_ > 1),
          Seq(Failure(exception), Success(2))
        ), (
          _.collectF {
            case 2 => "moin"
          }(exception2),
          Seq(Failure(exception2), Failure(exception), Success("moin"))
        ), (
          _.collectS {
            case 2 => "moin"
          },
          Seq(Failure(exception), Success("moin"))
        ), (
          _.mapConcatF(element => Seq(element, element)),
          Seq(Success(1), Success(1), Failure(exception), Success(2), Success(2))
        )
      )

      checkTable[Int, Try](input, operations)
    }
    "Try flattenF" in {
      Source(Seq(Success(1), Failure(new Exception), Success(2)))
        .flattenF
        .runWith(Sink.seq)
        .futureValue shouldBe Seq(1, 2)
    }
    "Try flatMapMergeF" in {
      val exception = new Exception
      Source(Seq(Success(1), Failure(exception), Success(2)))
        .flatMapMergeF(8, element => Source(Seq(element * 2, element * 4)))
        .runWith(Sink.seq)
        .futureValue should contain theSameElementsAs Seq(Success(2), Success(4), Failure(exception), Success(4), Success(8))
    }
    "Generic Try in Flow" in {
      val flow: Flow[Try[(Int, String)], Try[Either[Int, String]], NotUsed] = Flow[Try[(Int, String)]]
        .flatMapF(value => Success[Either[Int, String]](Left(value._1)))

      Source.single[Try[(Int, String)]](Success(1 -> "moin"))
        .via(flow)
        .runWith(Sink.seq)
        .futureValue shouldBe Seq(Success(Left(1)))
    }
    "Flow flattenF" in {
      Source(Seq(Some(1), None, Some(2)))
        .via(Flow[Option[Int]].flattenF)
        .runWith(Sink.seq)
        .futureValue shouldBe Seq(1, 2)
    }
  }

  private def checkTable[S, T[_]](input: Seq[T[S]], operations: TableFor2[Source[T[S], NotUsed] => Source[T[_], NotUsed], Seq[T[_]]]) = {
    forAll(operations) { case (operation, output) =>
      operation(Source(input))
        .runWith(Sink.seq)
        .futureValue shouldBe output
    }
  }

  private def checkTable2[L, R, T[_, _]](input: Seq[T[L, R]], operations: TableFor2[Source[T[L, R], NotUsed] => Source[T[_, _], NotUsed], Seq[T[_, _]]]) = {
    forAll(operations) { case (operation, output) =>
      operation(Source(input))
        .runWith(Sink.seq)
        .futureValue shouldBe output
    }
  }
}
