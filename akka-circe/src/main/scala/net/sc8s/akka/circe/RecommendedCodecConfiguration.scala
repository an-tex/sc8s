package net.sc8s.akka.circe

import io.circe.generic.extras.Configuration

trait RecommendedCodecConfiguration {
  val discriminator = "class"

  implicit val configuration = Configuration.default.withDiscriminator(discriminator)
}

object RecommendedCodecConfiguration extends RecommendedCodecConfiguration

