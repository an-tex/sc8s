package net.sc8s.schevo.circe

import io.circe.generic.extras.semiauto._
import io.circe.parser._
import io.circe.syntax.EncoderOps
import io.circe.{Codec, Decoder}
import net.sc8s.circe.CodecConfiguration._
import net.sc8s.schevo.circe.SchevoCirceSpec.{Other, Version2, codecExpectingDiscriminator, renamedClassCodec}
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class SchevoCirceSpec extends AnyWordSpecLike with Matchers with EitherValues {
  "CirceEvolution" should {
    "evolve from Version0 without discriminator, e.g. when schevo was introduced in retrospect" in {
      parse("""{"int":3}""").value.as[SchevoCirceSpec.Latest].value shouldBe Version2("moin3", "moin3")
    }
    "evolve from Version0 with discriminator, e.g. when schevo was introduced from the start" in {
      parse("""{"int":3,"class":"Version0"}""").value.as[SchevoCirceSpec.Latest](codecExpectingDiscriminator).value shouldBe Version2("moin3", "moin3")
    }
    "evolve from Version0 with discriminator from renamed class" in {
      parse("""{"int":3,"class":"Version"}""").value.as[SchevoCirceSpec.Latest](renamedClassCodec).value shouldBe Version2("moin3", "moin3")
    }
    "serialize from Latest trait including class discriminator" in {
      Version2("sup", "sup").asLatest.asJson.noSpaces shouldBe """{"string":"sup","string2":"sup","class":"Version2"}"""
    }
    // can't get the to work due to stack overflow in the encoder :(
    //"serialize from LatestCaseClass including class discriminator" in {
    //  Version2("sup","sup").asJson.noSpaces shouldBe """{"string":"sup","string2":"sup","class":"Version2"}"""
    //}
    "evolve in nested class" in {
      parse("""{"versioned":{"int":3}}""").value.as[Other].value shouldBe Other(Version2("moin3", "moin3"))
    }
  }
}

object SchevoCirceSpec extends SchevoCirce {
  override type LatestCaseClass = Version2

  sealed trait Version extends VersionT

  sealed trait Latest extends LatestT with Version

  case class Version2(string: String, string2: String) extends Latest {
    override def caseClass = this

    override def evolve: Latest = this
  }

  case class Version1(string: String) extends Version {
    override def evolve = Version2(string, string).evolve
  }

  case class Version0(int: Int) extends Version {
    override def evolve = Version1(s"moin${int.toString}").evolve
  }

  override implicit val codec: Codec[Latest] = evolvingCodec(classOf[Version0])(deriveConfiguredCodec)

  val codecExpectingDiscriminator: Codec[Latest] = evolvingCodec(classOf[Version0])(deriveConfiguredCodec)

  val renamedClassCodec: Codec[Latest] = evolvingCodec(classOf[Version0], "Version")(deriveConfiguredCodec)

  case class Other(versioned: Latest)
  object Other {
    implicit val decoder: Decoder[Other] = deriveConfiguredDecoder
  }
}

