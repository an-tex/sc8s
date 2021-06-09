package net.sc8s.akka.circe

import akka.Done
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.pattern.StatusReply
import io.circe.syntax._
import net.sc8s.akka.circe.StatusReplyCodecs._
import org.scalatest.BeforeAndAfterEach
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
