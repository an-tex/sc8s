package net.sc8s.akka

import akka.serialization.circe.InternalAkkaRefCodecs
import net.sc8s.circe.CodecConfiguration

package object circe {
  object implicits extends CodecConfiguration with InternalAkkaRefCodecs with StatusReplyCodecs
}
