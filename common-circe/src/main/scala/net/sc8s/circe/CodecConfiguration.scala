package net.sc8s.circe

import io.circe.generic.extras.Configuration

trait CodecConfiguration {
  val discriminator = "class"

  implicit val configuration = Configuration.default.withDiscriminator(discriminator)
}

object CodecConfiguration extends CodecConfiguration

