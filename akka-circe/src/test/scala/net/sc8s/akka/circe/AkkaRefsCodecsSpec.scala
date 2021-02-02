package net.sc8s.akka.circe

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.serialization.circe.SerializationHelper
import akka.stream.scaladsl.StreamRefs
import akka.stream.{SinkRef, SourceRef}
import io.circe.parser._
import io.circe.syntax._
import net.sc8s.akka.circe.AkkaRefCodecs._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers

class AkkaRefsCodecsSpec extends ScalaTestWithActorTestKit with AnyFreeSpecLike with Matchers with BeforeAndAfterEach {

  "AkkaRefCodecs should" - {
    "encode" - {
      "ActorRef" in {
        val probe = TestProbe[String]()

        parse(probe.ref.asJson.noSpaces).flatMap(_.as[ActorRef[String]]) shouldBe Right(probe.ref)
      }
      "SinkRef" in {
        val sinkRef = StreamRefs.sinkRef[String]().preMaterialize()._1

        parse(sinkRef.asJson.noSpaces).flatMap(_.as[SinkRef[String]]) shouldBe Right(sinkRef)
      }
      "SourceRef" in {
        val sourceRef = StreamRefs.sourceRef[String]().preMaterialize()._1

        parse(sourceRef.asJson.noSpaces).flatMap(_.as[SourceRef[String]]) shouldBe Right(sourceRef)
      }
    }
  }

  override protected def beforeEach() = SerializationHelper.setCurrentTransportInformation(testKit.system)
}
