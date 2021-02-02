package net.sc8s.akka

import akka.serialization.circe.InternalAkkaRefCodecs

package object circe {
  object implicits extends RecommendedCodecConfiguration with InternalAkkaRefCodecs with StatusReplyCodecs
}
