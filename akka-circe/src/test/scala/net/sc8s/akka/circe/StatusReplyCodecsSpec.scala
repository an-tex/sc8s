package net.sc8s.akka.circe

import akka.Done
import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.pattern.StatusReply
import akka.serialization.circe.SerializationHelper
import akka.stream.scaladsl.StreamRefs
import akka.stream.{SinkRef, SourceRef}
import io.circe.Codec
import io.circe.parser._
import io.circe.syntax._
import net.sc8s.akka.circe.StatusReplyCodecs._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class StatusReplyCodecsSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers with BeforeAndAfterEach {

  "StatusReplyCodecs" should {
    "be provided but throw an exception" in {
      an[Exception] should be thrownBy StatusReply.success(true).asJson
      an[Exception] should be thrownBy Done.done().asJson
    }
  }
}
