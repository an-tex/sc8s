package net.sc8s.schevo

import net.sc8s.schevo.SchevoSpec.Full
import net.sc8s.schevo.SchevoSpec.Minimal
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class SchevoSpec extends AnyWordSpecLike with Matchers {
  "Schevo" should {
    "evolve case class with minimal overrides" in {
      import Minimal._
      val itemV1 = ItemV1("first", "last")

      val migrated = itemV1.evolve

      migrated shouldBe a[Latest]
      migrated shouldBe ItemV3("first last", enabled = true)
      migrated.caseClass shouldBe a[ItemV3]
      // this just shows how you could obtain the latest trait when using e.g. circe
    }
    "evolve case class with full overrides" in {
      import Full._
      val itemV1 = ItemV1("first", "last")

      val migrated = itemV1.evolve

      migrated shouldBe
      // no need to reference actual latest case class
        Full.apply("first last", true)

      // this just shows how you could obtain the latest trait when using e.g. circe
      migrated.caseClass.asTrait shouldBe a[Latest]

      // common ancestor
      migrated shouldBe a[SomeOtherBaseClassHigherUp]
    }
    "evolve using base trait" in {
      import Minimal._
      val itemV1 = ItemV1("first", "last")

      Seq(itemV1: Any).collect {
        case item: Schevo.VersionBase[_] => item.evolve
      } shouldBe Seq(itemV1.evolve)
    }
  }
}

object SchevoSpec {
  object Minimal extends Schevo {
    override type LatestCaseClass = ItemV3

    case class ItemV3(name: String, enabled: Boolean) extends Latest {
      override def caseClass = this

      override def evolve = this
    }

    case class ItemV2(name: String) extends Version {
      override def evolve = ItemV3(name, enabled = true)
    }

    case class ItemV1(firstName: String, lastName: String) extends Version {
      override def evolve = ItemV2(s"$firstName $lastName").evolve
    }
  }

  object Full extends Schevo {
    sealed trait SomeOtherBaseClassHigherUp

    def apply = ItemV3.apply _

    trait Latest extends super.Latest {
      val name: String
      val enabled: Boolean

      override def evolve = this

      // optional but handy when using circe to make sure it uses the base trait for serialization instead of a concrete class
      def asTrait = this
    }

    override type LatestCaseClass = ItemV3

    case class ItemV3(name: String, enabled: Boolean) extends Latest with Version {
      override def caseClass = this
    }

    trait Version extends super.Version with SomeOtherBaseClassHigherUp

    case class ItemV2(name: String) extends Version {
      override def evolve = ItemV3(name, enabled = true)
    }

    case class ItemV1(firstName: String, lastName: String) extends Version {
      override def evolve = ItemV2(s"$firstName $lastName").evolve
    }
  }
}
