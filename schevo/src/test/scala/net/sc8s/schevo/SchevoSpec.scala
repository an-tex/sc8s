package net.sc8s.schevo

import net.sc8s.schevo.SchevoSpec.Item.{ItemV1, ItemV3, Latest}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class SchevoSpec extends AnyWordSpecLike with Matchers {
  "Schevo" should {
    "migrate" in {
      val itemV1 = ItemV1("first", "last")

      val migrated = itemV1.migrate

      migrated shouldBe a[Latest]
      migrated shouldBe ItemV3("first last", enabled = true)
      migrated.caseClass shouldBe a[ItemV3]
      // this just shows how you could obtain the latest trait when using e.g. circe
      migrated.caseClass.asTrait shouldBe a[Latest]
    }
  }
}

object SchevoSpec {
  object Item extends Schevo {
    trait Latest {
      val name: String
      val enabled: Boolean

      def caseClass: LatestCaseClass

      // optional but handy when using circe to make sure it uses the base trait for serialization instead of a concrete class
      def asTrait = this
    }

    override type LatestCaseClass = ItemV3

    case class ItemV3(name: String, enabled: Boolean) extends Latest {
      override def caseClass = this
    }

    case class ItemV2(name: String) extends Previous {
      override def migrate = ItemV3(name, enabled = true)
    }

    case class ItemV1(firstName: String, lastName: String) extends Previous {
      override def migrate = ItemV2(s"$firstName $lastName").migrate
    }
  }
}
