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

    // helper for atd based serialization
    lazy val asLatest: Latest = evolve
  }

  trait VersionT extends Schevo.VersionBase[Latest]

  // if you migrate from a simple case class to a Versioned trait, use sth like `implicit val codec: Codec[Latest] = evolvingCodec(classOf[Version0])(deriveConfiguredCodec)`
  implicit val codec: Codec[Latest]

  /**
   * create codec which automatically evolves a Versioned sealed trait.
   * use this if schevo is introduced from the start
   */
  def evolvingCodec(
                     implicit codec: Codec[Version]
                   ): Codec[Latest] = {
    Codec.from[Latest](
      codec.map(_.evolve),
      codec.contramap[Latest](a => a.asInstanceOf[Version])
    )
  }

  /**
   * create codec which automatically evolves a Versioned sealed trait.
   * use this if the Version trait is at the top of the serialization
   * and if schevo was introduced after a case class has already been used
   *
   * @param version0: initial version
   */
  def evolvingCodec(
                     version0: Class[_ <: Version],
                   )(
                     implicit codec: Codec[Version]
                   ): Codec[Latest] = {
    Codec.from[Latest](
      codec
        .prepare(_.withFocus(_.mapObject { obj =>
          if (!obj.contains(discriminator)) {
            obj.add(discriminator, version0.getSimpleName.asJson)
          }
          else obj
        }))
        .map(_.evolve),
      codec.contramap[Latest](a => a.asInstanceOf[Version])
    )
}

  /**
   * create codec which automatically evolves a Versioned sealed trait. use this if the Version trait is extending another trait which is serialized.
   *
   * @param version0: initial version
   * @param originalClassName: original class name before introduction of schevo
   */
  def evolvingCodec(
                     version0: Class[_ <: Version],
                     originalClassName: String
                   )(
                     implicit codec: Codec[Version]
                   ): Codec[Latest] = {
    Codec.from[Latest](
      codec
        .prepare(_.withFocus(_.mapObject { obj =>
          if (obj(discriminator).flatMap(_.asString).contains(originalClassName))
            obj.add(discriminator, version0.getSimpleName.asJson)
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
