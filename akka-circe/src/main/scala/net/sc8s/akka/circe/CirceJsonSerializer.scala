package net.sc8s.akka.circe

import akka.actor.ExtendedActorSystem
import akka.event.Logging
import akka.serialization.SerializerWithStringManifest
import io.circe.Printer
import io.circe.parser._
import io.circe.syntax.EncoderOps

import java.nio.charset.StandardCharsets
import scala.util.{Success, Try}

private[circe] final class CirceJsonSerializer(
                                                system: ExtendedActorSystem,
                                                registry: CirceSerializerRegistry,
                                              ) extends SerializerWithStringManifest {
  private val log = Logging.getLogger(system, getClass)

  // dropNullValues can cause issues with Map[_,Option[_]] https://github.com/circe/circe/issues/1544
  private val printer = Printer.noSpaces //.copy(dropNullValues = true)

  private val charset = StandardCharsets.UTF_8

  private val serializers: Map[String, CirceSerializer[AnyRef]] =
    registry.serializers.map(serializer =>
      manifest(serializer.entityClass) -> serializer.asInstanceOf[CirceSerializer[AnyRef]]
    ).toMap

  private val circeSerializerClassloaders = serializers.map(_._2.entityClass.getClassLoader).toList.distinct.to(LazyList)

  private val manifestRenames = serializers.values.flatMap(_.manifestRenames).toMap

  override def toBinary(o: AnyRef): Array[Byte] = {
    val manifest = this.manifest(o)
    log.debug(s"serializing $manifest")

    val serializer = findCodecAndMigrater(manifest)

    printer.print(serializer.codec(o)).getBytes(charset)
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    log.debug(s"deserializing $manifest")

    val serializer = findCodecAndMigrater(manifest)

    val json = parse(new String(bytes, charset)) match {
      case Left(value) =>
        throw value.copy(message = s"${value.message} of $manifest")

      case Right(value) =>
        value
    }

    val maybeRenamedClass = json.mapObject(obj =>
      (for {
        clazz <- obj("class").flatMap(_.asString)
        renamedTo <- serializer.descriptorRenames.get(clazz)
      } yield obj.add("class", renamedTo.asJson)).getOrElse(obj)
    )

    maybeRenamedClass.as(serializer.codec) match {
      case Left(value) => throw value.withMessage(s"${value.message} of $manifest")
      case Right(value) => serializer.migrate.unapply(value).getOrElse(value)
    }
  }

  override def identifier = 38495562

  private def allInterfaces(clazz: Class[_]): Seq[String] = {
    val interfaces = clazz.getInterfaces.toSeq
    if (interfaces.isEmpty) Seq(manifest(clazz))
    else manifest(clazz) +: interfaces.flatMap(allInterfaces)
  }

  private def findCodecAndMigrater(manifest: String) = {
    lazy val clazzFromClassLoaders = circeSerializerClassloaders
      .map(classLoader => Try(Class.forName(manifest, true, classLoader)))
      .collectFirst {
        case Success(clazz) => clazz
      }
      .head

    val clazz = manifestRenames.getOrElse(manifest, clazzFromClassLoaders)

    val classManifests = allInterfaces(clazz)
    serializers.find {
      case (value, _) => classManifests.contains(value)
    } match {
      case Some((_, serializer)) => serializer
      case None =>
        throw new RuntimeException(
          s"Missing circe serializer for [${classManifests.mkString(", ")}], " +
            s"defined are [${serializers.keys.mkString(", ")}]"
        )
    }
  }

  override def manifest(o: AnyRef) = manifest(o.getClass)

  def manifest(clazz: Class[_]) = clazz.getName
}
