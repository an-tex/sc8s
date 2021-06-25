package net.sc8s.schevo.circe

import io.circe.Codec
import io.circe.syntax.EncoderOps
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

    override def evolve = this.asInstanceOf[Latest]

    // helper for atd based serialization
    lazy val asLatest: Latest = evolve
  }

  trait VersionT extends Schevo.VersionBase[Latest]

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
   * @param version0 : initial version
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
}

object SchevoCirce {
  type SimpleClassName = String

  /**
   * create codec which automatically evolves a Versioned sealed trait inherited from a parent trait T
   *
   * @param classRenames: define classRenames in case you've renamed/moved a case class
   */
  def evolvingCodec[T](
                        classRenames: (SimpleClassName, SimpleClassName)*
                      )(
                        implicit codec: Codec[T]
                      ): Codec[T] = {
    val classRenamesMap = classRenames.toMap

    Codec.from[T](
      codec
        .prepare(_.withFocus(_.mapObject { obj =>
          (for {
            clazz <- obj(discriminator).flatMap(_.asString)
            renamedTo <- classRenamesMap.get(clazz)
          } yield obj.add(discriminator, renamedTo.asJson)).getOrElse(obj)
        }))
        .map {
          case version: Schevo.VersionBase[_] => version.evolve.asInstanceOf[T]
          case nonVersion => nonVersion
        },
      codec.contramap[T](identity)
    )
  }
}