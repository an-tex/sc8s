package net.sc8s.schevo.circe

import io.circe.generic.extras.semiauto._
import io.circe.parser._
import io.circe.syntax.EncoderOps
import io.circe.{Codec, Decoder}
import net.sc8s.circe.CodecConfiguration._
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class SchevoCirceSpec extends AnyWordSpecLike with Matchers with EitherValues {
  "CirceEvolution" should {
    import SchevoCirceSpec._

    "Simple" should {
      import Simple._
      val version2 = Version2(firstName, lastName, age)
      val version0Json = (Version0(age): Version).asJson(deriveConfiguredCodec)

      "evolve from Version0" in {
        version0Json.as[Latest].value shouldBe version2
      }
      "serialize from Latest trait" in {
        val latest: Latest = version2.asLatest
        latest.asJson.as[Latest].value shouldBe version2
      }
      "evolve in nested class" in {
        parse(s"""{"versioned":${version0Json.noSpaces}}""").value.as[Other].value shouldBe Other(version2)
      }
    }
    "Migrated" should {
      import Migrated._
      val version2 = Version2(firstName, lastName, age)
      val unversionedJson = Unversioned(age).asJson

      "evolve from Version0" in {
        unversionedJson.as[Latest].value shouldBe version2
      }
      "serialize from Latest trait" in {
        val latest: Latest = version2.asLatest
        latest.asJson.as[Latest].value shouldBe version2
      }
      "evolve in nested class" in {
        parse(s"""{"versioned":${unversionedJson.noSpaces}}""").value.as[Other].value shouldBe Other(version2)
      }
    }
    "Inherited" should {
      import Inherited._
      import Inherited.VersionedChild._
      val version2 = Version2(firstName, lastName, age)
      val version0Json = (Version0(age): Version).asJson(deriveConfiguredCodec)

      "evolve from Version0" in {
        version0Json.as[Parent].value shouldBe version2
      }
      "serialize from Parent trait" in {
        (version2 : Parent).asJson.as[Parent].value shouldBe version2
      }
      "evolve in nested class" in {
        parse(s"""{"parent":${version0Json.noSpaces}}""").value.as[Other].value shouldBe Other(version2)
      }
    }
  }
}

object SchevoCirceSpec {
  val age = 37
  val firstName = "Jon"
  val lastName = "Doe"

  // example when introducing schevo from the start
  object Simple extends SchevoCirce {
    sealed trait Latest extends LatestT with Version

    override type LatestCaseClass = Version2

    case class Version2(firstName: String, lastName: String, age: Int) extends Latest {
      override def caseClass = this
    }

    case class Version1(firstName: String, age: Int) extends Version {
      override def evolve = Version2(firstName, lastName, age).evolve
    }

    case class Version0(age: Int) extends Version {
      override def evolve = Version1(firstName, age).evolve
    }

    sealed trait Version extends VersionT

    implicit val codec: Codec[Latest] = evolvingCodec(deriveConfiguredCodec[Version])

    case class Other(versioned: Latest)
    object Other {
      implicit val decoder: Decoder[Other] = deriveConfiguredDecoder
    }
  }

  // example when schevo is introduced in retrospect
  object Migrated extends SchevoCirce {
    // assume there used to be only Unversioned which becomes Version0
    @deprecated("use Version0")
    case class Unversioned(age: Int)
    object Unversioned {
      implicit val codec : Codec[Unversioned] = deriveConfiguredCodec
    }

    sealed trait Latest extends LatestT with Version

    override type LatestCaseClass = Version2

    case class Version2(firstName: String, lastName: String, age: Int) extends Latest {
      override def caseClass = this
    }

    case class Version1(firstName: String, age: Int) extends Version {
      override def evolve = Version2(firstName, lastName, age).evolve
    }

    case class Version0(age: Int) extends Version {
      override def evolve = Version1(firstName, age).evolve
    }

    sealed trait Version extends VersionT

    implicit val codec: Codec[Latest] = evolvingCodec(classOf[Version0])(deriveConfiguredCodec)

    case class Other(versioned: Latest)
    object Other {
      implicit val decoder: Decoder[Other] = deriveConfiguredDecoder
    }
  }

  // example when there is a parent trait and (some) children are versioned (e.g. State/Event traits)
  object Inherited {
    sealed trait Parent

    case class UnversionedChild() extends Parent

    object VersionedChild extends SchevoCirce {
      sealed trait Latest extends LatestT with Version

      override type LatestCaseClass = Version2

      case class Version2(firstName: String, lastName: String, age: Int) extends Latest {
        override def caseClass = this
      }

      case class Version1(firstName: String, age: Int) extends Version {
        override def evolve = Version2(firstName, lastName, age).evolve
      }

      case class Version0(age: Int) extends Version {
        override def evolve = Version1(firstName, age).evolve
      }

      sealed trait Version extends VersionT with Parent
    }

    implicit val codec: Codec[Parent] = SchevoCirce.evolvingCodec(deriveConfiguredCodec[Parent])

    case class Other(parent: Parent)
    object Other {
      implicit val decoder: Decoder[Other] = deriveConfiguredDecoder
    }
  }
}
