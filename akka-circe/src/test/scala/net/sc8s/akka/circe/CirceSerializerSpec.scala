package net.sc8s.akka.circe

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class CirceSerializerSpec extends AnyWordSpecLike with Matchers {
  "CirceSerializer" should {
    "include type parameter in hashCode & equals" in {
      case class A()
      case class B()
      implicit val codecA: Codec[A] = deriveCodec
      implicit val codecB: Codec[B] = deriveCodec
      val circeSerializerA1 = CirceSerializer[A]()
      val circeSerializerA2 = CirceSerializer[A]()
      val circeSerializerB = CirceSerializer[B]()
      Set(circeSerializerA1, circeSerializerB) should have size 2
      Set(circeSerializerA1, circeSerializerA2) should have size 1
    }
  }
}
