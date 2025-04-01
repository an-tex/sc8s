package net.sc8s.akka.circe

import akka.actor.ExtendedActorSystem
import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import akka.serialization.circe.SerializationHelper
import akka.stream.scaladsl.StreamRefs
import akka.stream.{SinkRef, SourceRef}
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.generic.semiauto.deriveCodec
import io.circe.parser._
import io.circe.syntax.EncoderOps
import net.sc8s.akka.circe.CirceJsonSerializerSpec.{ADT, ADT1, ADT2, ADTChild1, ADTChild2, SimpleCaseClass, WithSinkAndSourceRef, WithTypedActorRef}
import net.sc8s.akka.circe.implicits._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpecLike

import java.nio.charset.StandardCharsets

class CirceJsonSerializerSpec extends ScalaTestWithActorTestKit with AnyFreeSpecLike with BeforeAndAfterEach {
  val extendedActorSystem = system.toClassic.asInstanceOf[ExtendedActorSystem]

  "CirceJsonSerializer should" - {
    "encode & decode" - {
      "simple CaseClasses" in {
        val simpleCaseClass = SimpleCaseClass("moin")

        val registry: CirceSerializerRegistry = new CirceSerializerRegistry {
          override def serializers = Vector(CirceSerializer[SimpleCaseClass]())
        }
        val serializer = new CirceJsonSerializer(extendedActorSystem, registry)

        val bytes = serializer.toBinary(simpleCaseClass)

        parse(new String(bytes, StandardCharsets.UTF_8)) shouldBe Right(simpleCaseClass.asJson)
        serializer.fromBinary(bytes, manifest(simpleCaseClass.getClass)) shouldBe simpleCaseClass
      }
      "ADTs" in {
        val adt1 = ADT1("moin")
        val adt2 = ADT2(2)

        val registry: CirceSerializerRegistry = new CirceSerializerRegistry {
          override def serializers = Vector(CirceSerializer[ADT]())
        }
        val serializer = new CirceJsonSerializer(extendedActorSystem, registry)

        val bytes1 = serializer.toBinary(adt1)
        val bytes2 = serializer.toBinary(adt2)

        parse(new String(bytes1, StandardCharsets.UTF_8)) shouldBe Right(adt1.asInstanceOf[ADT].asJson)
        parse(new String(bytes2, StandardCharsets.UTF_8)) shouldBe Right(adt2.asInstanceOf[ADT].asJson)

        serializer.fromBinary(bytes1, manifest(adt1.getClass)) shouldBe adt1
        serializer.fromBinary(bytes2, manifest(adt2.getClass)) shouldBe adt2
      }
      "ADT children" in {
        val adt1 = ADTChild1(3)
        val adt2 = ADTChild2('a')

        val registry: CirceSerializerRegistry = new CirceSerializerRegistry {
          override def serializers = Vector(CirceSerializer[ADT]())
        }
        val serializer = new CirceJsonSerializer(extendedActorSystem, registry)

        val bytes1 = serializer.toBinary(adt1)
        val bytes2 = serializer.toBinary(adt2)

        parse(new String(bytes1, StandardCharsets.UTF_8)) shouldBe Right(adt1.asInstanceOf[ADT].asJson)
        parse(new String(bytes2, StandardCharsets.UTF_8)) shouldBe Right(adt2.asInstanceOf[ADT].asJson)

        serializer.fromBinary(bytes1, manifest(adt1.getClass)) shouldBe adt1
        serializer.fromBinary(bytes2, manifest(adt2.getClass)) shouldBe adt2
      }
      "ActorRefs" in {
        val probe = TestProbe[String]()

        val withTypedActorRef = WithTypedActorRef(probe.ref)

        val registry: CirceSerializerRegistry = new CirceSerializerRegistry {
          override def serializers = Vector(CirceSerializer[WithTypedActorRef]())
        }
        val serializer = new CirceJsonSerializer(extendedActorSystem, registry)

        val bytes = serializer.toBinary(withTypedActorRef)

        parse(new String(bytes, StandardCharsets.UTF_8)) shouldBe Right(withTypedActorRef.asJson)
        serializer.fromBinary(bytes, manifest(withTypedActorRef.getClass)) shouldBe withTypedActorRef
      }
      "SinkRefs & SourceRefs" in {
        val sinkRef = StreamRefs.sinkRef[String]().preMaterialize()._1
        val sourceRef = StreamRefs.sourceRef[String]().preMaterialize()._1

        val withSinkAndSourceRef = WithSinkAndSourceRef(sinkRef, sourceRef)

        val registry: CirceSerializerRegistry = new CirceSerializerRegistry {
          override def serializers = Vector(CirceSerializer[WithSinkAndSourceRef]())
        }
        val serializer = new CirceJsonSerializer(extendedActorSystem, registry)

        val bytes = serializer.toBinary(withSinkAndSourceRef)

        parse(new String(bytes, StandardCharsets.UTF_8)) shouldBe Right(withSinkAndSourceRef.asJson)
        serializer.fromBinary(bytes, manifest(withSinkAndSourceRef.getClass)) shouldBe withSinkAndSourceRef
      }
      // TODO move to separate module once schema evolution is open sourced?
      /*
      "supporting schemaEvolution" in {
        val v1 = Vx.V1(3)
        val v2 = Vx.V2("5")

        val registry: CirceSerializerRegistry = new CirceSerializerRegistry {
          override def serializers = Vector(CirceSerializer[Vx.Versioned]({
            case versioned: Vx.Versioned => versioned.migrate
          }: PartialFunction[Vx.Versioned, Vx.Versioned]))
        }

        val serializer = new CirceJsonSerializer(extendedActorSystem, registry)

        val bytes1 = serializer.toBinary(v1)
        val bytes2 = serializer.toBinary(v2)

        parse(new String(bytes1, StandardCharsets.UTF_8)) must beRight(v1.asInstanceOf[Vx.Versioned].asJson)
        parse(new String(bytes2, StandardCharsets.UTF_8)) must beRight(v2.asInstanceOf[Vx.Versioned].asJson)

        serializer.fromBinary(bytes1, manifest(v1.getClass)) shouldBe v1.migrate
        serializer.fromBinary(bytes2, manifest(v2.getClass)) shouldBe v2
      }
      "supporting manifestRenames" in {
        val registry: CirceSerializerRegistry = new CirceSerializerRegistry {
          override def serializers = Vector(
            CirceSerializer[Command]({
              case versioned: Vx.Versioned => versioned.migrate
            }, Map(
              "net.sc8s.circe.akka.CirceJsonSerializerSpec.Vz" -> Vx.V0.getClass,
              "net.sc8s.circe.akka.CirceJsonSerializerSpec$Vz" -> Vx.V0.getClass,
              "net.sc8s.circe.akka.CirceJsonSerializerSpec$Vz$" -> Vx.V0.getClass
            )))
        }

        val serializer = new CirceJsonSerializer(extendedActorSystem, registry)

        val bytes = """{"boolean":true,"class":"Vz"}""".getBytes
        serializer.fromBinary(bytes, "net.sc8s.circe.akka.CirceJsonSerializerSpec.Vz") shouldBe Vx.V0.migrate
        serializer.fromBinary(bytes, "net.sc8s.circe.akka.CirceJsonSerializerSpec$Vz") shouldBe Vx.V0.migrate
        serializer.fromBinary(bytes, "net.sc8s.circe.akka.CirceJsonSerializerSpec$Vz$") shouldBe Vx.V0.migrate
      }
      */
    }
  }

  def manifest(clazz: Class[_]) = clazz.getName

  override protected def beforeEach() = SerializationHelper.setCurrentTransportInformation(testKit.system)
}

object CirceJsonSerializerSpec {

  val discriminator = "class"

  // necessary when introducing schema evolution to an existing class
  implicit val configuration = Configuration.default.withDiscriminator(discriminator)

  case class SimpleCaseClass(string: String)
  object SimpleCaseClass {
    implicit val codec: Codec[SimpleCaseClass] = deriveCodec
  }

  sealed trait ADT
  case class ADT1(string: String) extends ADT
  case class ADT2(int: Int) extends ADT

  sealed trait ADTChild extends ADT
  case class ADTChild1(long: Long) extends ADTChild
  case class ADTChild2(char: Char) extends ADTChild

  object ADT {
    implicit val codec: Codec[ADT] = deriveCodec
  }

  case class WithTypedActorRef(replyTo: ActorRef[String])
  object WithTypedActorRef {
    implicit val codec: Codec[WithTypedActorRef] = deriveCodec
  }

  case class WithSinkAndSourceRef(sinkRef: SinkRef[String], sourceRef: SourceRef[String])
  object WithSinkAndSourceRef {
    implicit val codec: Codec[WithSinkAndSourceRef] = deriveCodec
  }

  sealed trait Command
  object Command {
    implicit val codec: Codec[Command] = deriveConfiguredCodec
  }

  // used to be this, but moved to Vx.V0 for migration purposes
  //case object Vz extends Command

  object Vx {
    sealed trait Versioned extends Command {
      def migrate: Versioned
    }

    object Versioned {
      implicit val codec: Codec[Versioned] = deriveConfiguredCodec
    }

    case object V0 extends Versioned {
      override def migrate = V1(3).migrate
    }

    case class V1(long: Long) extends Versioned {
      override def migrate = V2(s"stringed:$long").migrate
    }

    case class V2(string: String) extends Versioned {
      override def migrate = this
    }
  }
}
