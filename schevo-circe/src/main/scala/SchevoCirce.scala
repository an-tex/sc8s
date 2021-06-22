package net.sc8s.schevo.circe

import io.circe.syntax.EncoderOps
import io.circe.{Codec, Encoder}
import net.sc8s.circe.CodecConfiguration.discriminator
import net.sc8s.schevo.Schevo

trait SchevoCirce {
  // point this to your case class
  type LatestCaseClass <: LatestT

  // create sealed traits overriding these types for ADT de/encoding
  type Latest <: LatestT with Version
  type Version <: VersionT

  trait LatestT extends VersionT {
    // esp. useful for _.copy
    def caseClass: LatestCaseClass
  }

  trait VersionT extends Schevo.VersionBase[Latest] {
    type LatestTrait = Latest
  }

  def evolvingCodec(
                     version0: Class[_ <: Version]
                   )(
                     implicit codec: Codec[Version]
                   ): Codec[Latest] = {
    Codec.from[Latest](
      codec
        .prepare(_.withFocus(_.mapObject { obj =>
          if (!obj.contains(discriminator)) {
            obj.+:(discriminator -> version0.getSimpleName.asJson)
          }
          else obj
        }))
        .map(_.evolve),
      codec.contramap[Latest](a => a.asInstanceOf[Version])
    )
  }

  def latestCaseClassEncoder[Latest >: LatestCaseClass](implicit encoder: => Encoder[Latest]) = new Encoder[LatestCaseClass] {
    override def apply(a: LatestCaseClass) = (a: Latest).asJson(encoder)
  }
}
