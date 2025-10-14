package net.sc8s.akka.stream

import akka.NotUsed
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.stream.scaladsl.{Flow, FlowWithContext, Sink, Source}
import cats.implicits.{catsStdInstancesForEither, catsStdInstancesForOption, catsStdInstancesForTry, catsStdTraverseFilterForOption}
import izumi.logstage.api.IzLogger
import izumi.logstage.api.Log.Level
import izumi.logstage.sink.ConsoleSink.SimpleConsoleSink
import net.sc8s.akka.stream.implicits._
import org.scalatest.prop.{TableDrivenPropertyChecks, TableFor2}
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

class StreamOpsSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with TableDrivenPropertyChecks {
  implicit val executionContext: ExecutionContextExecutor = testKit.system.executionContext

  implicit val consoleLogger: IzLogger = IzLogger(Level.Trace, SimpleConsoleSink)

  "StreamOps" should {
    val mapAsyncOperation = { element: Int => Future.successful(element * 2) }

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

      val flatMapAsyncOperation = { element: Int => Future.successful(Some(element * 2).filter(_ == 4)) }
      val flatMapResult = Seq(None, None, Some(4))
      val mapResult = Seq(Some(2), None, Some(4))

      val operations = Table[
        Source[Option[Int], NotUsed] => Source[Option[_], NotUsed],
        Seq[Option[_]],
      ](
        (
          "Operation",
          "Results",
        ), (
          _.mapF(_ * 2),
          mapResult
        ), (
          _.flatMapF(element => Some(element * 2).filter(_ == 4)),
          flatMapResult
        ), (
          _.flatMapConcatF(element => Source(Seq(element * 2, element * 4))),
          Seq(Some(2), Some(4), None, Some(4), Some(8))
        ), (
          _.mapAsyncF(1)(mapAsyncOperation),
          mapResult
        ), (
          _.flatMapAsyncF(1)(flatMapAsyncOperation),
          flatMapResult
        ), (
          _.mapAsyncUnorderedF(1)(mapAsyncOperation),
          mapResult
        ), (
          _.flatMapAsyncUnorderedF(1)(flatMapAsyncOperation),
          flatMapResult
        ), (
          _.mapAsyncRetryWithBackoffF(1)(mapAsyncOperation),
          mapResult
        ), (
          _.flatMapAsyncRetryWithBackoffF(1)(flatMapAsyncOperation),
          flatMapResult
        ), (
          _.mapAsyncUnorderedRetryWithBackoffF(1)(mapAsyncOperation),
          mapResult
        ), (
          _.flatMapAsyncUnorderedRetryWithBackoffF(1)(flatMapAsyncOperation),
          flatMapResult
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
        ), (
          _.mapConcatF(element => Seq(s"x-$element", s"y-$element")),
          Seq(Some("x-1"), Some("y-1"), None, Some("x-2"), Some("y-2"))
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
    "Option groupByF" in {
      Source(Seq(Some(1), None, Some(2), Some(3), None, Some(4)))
        .groupByF(Int.MaxValue, _ > 2).fold(List.empty[Option[Int]])(_ :+ _).mergeSubstreams
        .runWith(Sink.seq)
        .futureValue should contain theSameElementsAs Seq(Seq(Some(1), Some(2)), Seq(Some(3), Some(4)), Seq(None, None))
    }
    "Option foldF" in {
      Source(Seq(Some(1), None, Some(2), Some(3), None, Some(4)))
        .foldF(Seq.empty[Int])(_ :+ _)
        .runWith(Sink.seq)
        .futureValue should contain theSameElementsAs Seq(None, None, Some(Seq(1, 2, 3, 4)))
    }
    "Option foldF with no Some" in {
      Source(Seq(None, None))
        .foldF(Seq.empty[Int])(_ :+ _)
        .runWith(Sink.seq)
        .futureValue should contain theSameElementsAs Seq(None, None, Some(Nil))
    }
    "Option foldS" in {
      Source(Seq(Some(1), None, Some(2), Some(3), None, Some(4)))
        .foldS(Seq.empty[Int])(_ :+ _)
        .runWith(Sink.seq)
        .futureValue should contain theSameElementsAs Seq(None, None, Some(Seq(1, 2, 3, 4)))
    }
    "Option foldS with no Some" in {
      Source(Seq(None, None))
        .foldS(Seq.empty[Int])(_ :+ _)
        .runWith(Sink.seq)
        .futureValue should contain theSameElementsAs Seq(None, None)
    }
    "Option alsoToF" in {
      val sideOutput = scala.collection.mutable.ListBuffer.empty[Int]
      Source(Seq(Some(1), None, Some(2), Some(3), None, Some(4)))
        .alsoToF(Sink.foreach[Int](sideOutput += _))
        .runWith(Sink.seq)
        .futureValue shouldBe Seq(Some(1), None, Some(2), Some(3), None, Some(4))
      sideOutput.toSeq shouldBe Seq(1, 2, 3, 4)
    }
    "Either" in {
      val input = Seq(Right(1), Left(true), Right(2))

      val flatMapAsyncOperation = { element: Int => Future.successful(if (element == 2) Right(element * 2) else Left(false)) }
      val flatMapResult = Seq(Left(false), Left(true), Right(4))
      val mapResult = Seq(Right(2), Left(true), Right(4))

      val operations = Table[
        Source[Either[Boolean, Int], NotUsed] => Source[Either[_, _], NotUsed],
        Seq[Either[_, _]],
      ](
        (
          "Operation",
          "Output",
        ), (
          _.mapF(_ * 2),
          mapResult

        ), (
          _.mapAsyncF(1)(mapAsyncOperation),
          mapResult
        ), (
          _.flatMapAsyncF(1)(flatMapAsyncOperation),
          flatMapResult
        ), (
          _.mapAsyncUnorderedF(1)(mapAsyncOperation),
          mapResult
        ), (
          _.flatMapAsyncUnorderedF(1)(flatMapAsyncOperation),
          flatMapResult
        ), (
          _.mapAsyncRetryWithBackoffF(1)(mapAsyncOperation),
          mapResult
        ), (
          _.flatMapAsyncRetryWithBackoffF(1)(flatMapAsyncOperation),
          flatMapResult
        ), (
          _.mapAsyncUnorderedRetryWithBackoffF(1)(mapAsyncOperation),
          mapResult
        ), (
          _.flatMapAsyncUnorderedRetryWithBackoffF(1)(flatMapAsyncOperation),
          flatMapResult
        ), (
          _.flatMapF(element => if (element == 2) Right(element * 2) else Left(false)),
          flatMapResult
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
        ), (
          _.mapConcatF(element => Seq(s"x-$element", s"y-$element")),
          Seq(Right("x-1"), Right("y-1"), Left(true), Right("x-2"), Right("y-2"))
        ), (
          _.viaF(Flow[Int].map(_ * 2)),
          Seq(Right(2), Left(true), Right(4))
        )
      )

      checkTable2[Boolean, Int, Either](input, operations)
    }
    "Either collectLeftF" in {
      Source(Seq(Right(1), Left(true), Right(2)))
        .collectLeftF
        .runWith(Sink.seq)
        .futureValue shouldBe Seq(true)
    }
    "Either collectRightF" in {
      Source(Seq(Right(1), Left(true), Right(2)))
        .collectRightF
        .runWith(Sink.seq)
        .futureValue shouldBe Seq(1, 2)
    }
    "Either flattenF" in {
      Source(Seq(Right(Right(1)), Left(true), Right(Left(false)), Right(Right(2))))
        .flattenF
        .runWith(Sink.seq)
        .futureValue shouldBe Seq(Right(1), Left(true), Left(false), Right(2))
    }
    "Either flatMapMergeF" in {
      Source(Seq(Right(1), Left(true), Right(2)))
        .flatMapMergeF(8, element => Source(Seq(element * 2, element * 4)))
        .runWith(Sink.seq)
        .futureValue should contain theSameElementsAs Seq(Right(2), Right(4), Left(true), Right(4), Right(8))
    }
    "Either groupByF" in {
      Source(Seq(Right(1), Left(true), Right(2), Right(3), Left(false), Right(4)))
        .groupByF(Int.MaxValue, _ > 2).fold(Seq.empty[Either[Boolean, Int]])(_ :+ _).mergeSubstreams
        .runWith(Sink.seq)
        .futureValue should contain theSameElementsAs Seq(Seq(Left(true), Left(false)), Seq(Right(1), Right(2)), Seq(Right(3), Right(4)))
    }
    "Either foldF" in {
      Source(Seq(Right(1), Left(true), Right(2), Right(3), Left(false), Right(4)))
        .foldF(Seq.empty[Int])(_ :+ _)
        .runWith(Sink.seq)
        .futureValue should contain theSameElementsAs Seq(Left(true), Left(false), Right(Seq(1, 2, 3, 4)))
    }
    "Either foldF with no Rights" in {
      Source(Seq(Left(false), Left(true)))
        .foldF(Seq.empty[Int])(_ :+ _)
        .runWith(Sink.seq)
        .futureValue should contain theSameElementsAs Seq(Left(true), Left(false), Right(Nil))
    }
    "Either foldS" in {
      Source(Seq(Right(1), Left(true), Right(2), Right(3), Left(false), Right(4)))
        .foldS(Seq.empty[Int])(_ :+ _)
        .runWith(Sink.seq)
        .futureValue should contain theSameElementsAs Seq(Left(true), Left(false), Right(Seq(1, 2, 3, 4)))
    }
    "Either foldS with no Rights" in {
      Source(Seq(Left(false), Left(true)))
        .foldS(Seq.empty[Int])(_ :+ _)
        .runWith(Sink.seq)
        .futureValue should contain theSameElementsAs Seq(Left(true), Left(false))
    }
    "Either Subflow foldF" in {
      Source(Seq(Right(1), Left(true), Right(2), Right(3), Left(false), Right(4)))
        .groupBy(Int.MaxValue, _ => true)
        .foldF(Seq.empty[Int])(_ :+ _)
        .mergeSubstreams
        .runWith(Sink.seq)
        .futureValue should contain theSameElementsAs Seq(Left(true), Left(false), Right(Seq(1, 2, 3, 4)))
    }
    "Either alsoToF" in {
      val sideOutput = scala.collection.mutable.ListBuffer.empty[Int]
      Source(Seq(Right(1), Left(true), Right(2), Right(3), Left(false), Right(4)))
        .alsoToF(Sink.foreach[Int](sideOutput += _))
        .runWith(Sink.seq)
        .futureValue shouldBe Seq(Right(1), Left(true), Right(2), Right(3), Left(false), Right(4))
      sideOutput.toSeq shouldBe Seq(1, 2, 3, 4)
    }
    "Try" in {
      val exception = new Exception
      val exception2 = new Exception
      val input: Seq[Try[Int]] = Seq(Success(1), Failure(exception), Success(2))

      val flatMapAsyncOperation = { element: Int => Future.successful(if (element == 2) Success(element * 2) else Failure(exception2)) }
      val mapResult = Seq(Success(2), Failure(exception), Success(4))
      val flatMapResult = Seq(Failure(exception2), Failure(exception), Success(4))

      val operations: TableFor2[Source[Try[Int], NotUsed] => Source[Try[_], NotUsed], Seq[Try[_]]] = Table[
        Source[Try[Int], NotUsed] => Source[Try[_], NotUsed],
        Seq[Try[_]],
      ](
        (
          "Operation",
          "Results",
        ), (
          _.mapF(_ * 2),
          mapResult
        ), (
          _.mapAsyncF(1)(mapAsyncOperation),
          mapResult
        ), (
          _.flatMapAsyncF(1)(flatMapAsyncOperation),
          flatMapResult
        ), (
          _.mapAsyncUnorderedF(1)(mapAsyncOperation),
          mapResult
        ), (
          _.flatMapAsyncUnorderedF(1)(flatMapAsyncOperation),
          flatMapResult
        ), (
          _.mapAsyncRetryWithBackoffF(1)(mapAsyncOperation),
          mapResult
        ), (
          _.flatMapAsyncRetryWithBackoffF(1)(flatMapAsyncOperation),
          flatMapResult
        ), (
          _.mapAsyncUnorderedRetryWithBackoffF(1)(mapAsyncOperation),
          mapResult
        ), (
          _.flatMapAsyncUnorderedRetryWithBackoffF(1)(flatMapAsyncOperation),
          flatMapResult
        ), (
          _.flatMapF(element => if (element == 2) Success(element * 2) else Failure(exception2)),
          flatMapResult
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
    "Try groupByF" in {
      val exception1 = new Exception
      val exception2 = new Exception
      Source(Seq(Success(1), Failure(exception1), Success(2), Success(3), Failure(exception2), Success(4)))
        .groupByF(Int.MaxValue, _ > 2).fold(List.empty[Try[Int]])(_ :+ _).mergeSubstreams
        .runWith(Sink.seq)
        .futureValue should contain theSameElementsAs Seq(Seq(Success(1), Success(2)), Seq(Failure(exception1), Failure(exception2)), Seq(Success(3), Success(4)))
    }
    "Try foldF" in {
      val exception1 = new Exception
      val exception2 = new Exception
      Source(Seq(Success(1), Failure(exception1), Success(2), Success(3), Failure(exception2), Success(4)))
        .foldF(Seq.empty[Int])(_ :+ _)
        .runWith(Sink.seq)
        .futureValue should contain theSameElementsAs Seq(Failure(exception1), Failure(exception2), Success(Seq(1, 2, 3, 4)))
    }
    "Try foldF with no Success" in {
      val exception1 = new Exception
      val exception2 = new Exception
      Source(Seq(Failure(exception1), Failure(exception2)))
        .foldF(Seq.empty[Int])(_ :+ _)
        .runWith(Sink.seq)
        .futureValue should contain theSameElementsAs Seq(Failure(exception1), Failure(exception2), Success(Nil))
    }
    "Try foldS" in {
      val exception1 = new Exception
      val exception2 = new Exception
      Source(Seq(Success(1), Failure(exception1), Success(2), Success(3), Failure(exception2), Success(4)))
        .foldS(Seq.empty[Int])(_ :+ _)
        .runWith(Sink.seq)
        .futureValue should contain theSameElementsAs Seq(Failure(exception1), Failure(exception2), Success(Seq(1, 2, 3, 4)))
    }
    "Try foldS with no Success" in {
      val exception1 = new Exception
      val exception2 = new Exception
      Source(Seq(Failure(exception1), Failure(exception2)))
        .foldS(Seq.empty[Int])(_ :+ _)
        .runWith(Sink.seq)
        .futureValue should contain theSameElementsAs Seq(Failure(exception1), Failure(exception2))
    }
    "Try alsoToF" in {
      val exception1 = new Exception
      val exception2 = new Exception
      val sideOutput = scala.collection.mutable.ListBuffer.empty[Int]
      Source(Seq(Success(1), Failure(exception1), Success(2), Success(3), Failure(exception2), Success(4)))
        .alsoToF(Sink.foreach[Int](sideOutput += _))
        .runWith(Sink.seq)
        .futureValue shouldBe Seq(Success(1), Failure(exception1), Success(2), Success(3), Failure(exception2), Success(4))
      sideOutput.toSeq shouldBe Seq(1, 2, 3, 4)
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
    "FlowWithContext operators" in {
      Flow[Option[Int]].flattenF
      FlowWithContext[Option[Int], Boolean].flatMapF(Option(_))
      FlowWithContext[Option[Int], Boolean].mapAsyncF(1)(Future.successful)
      Flow[Int].mapAsyncUnorderedRetryWithBackoff(8)(Future.successful)
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
