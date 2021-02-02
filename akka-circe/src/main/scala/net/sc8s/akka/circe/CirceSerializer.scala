package net.sc8s.akka.circe

import io.circe.Codec

import scala.reflect.ClassTag

case class CirceSerializer[T: ClassTag : Codec](
                                                 migrate: PartialFunction[T, T] = PartialFunction.empty,
                                                 manifestRenames: Map[String, Class[_]] = Map.empty[String, Class[_]]
                                               ) {
  val entityClass: Class[T] = implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]]

  val codec: Codec[T] = implicitly[Codec[T]]

  private val descriptorRewrite = ".*[$.]([^$]+)[$]?$".r
  val descriptorRenames = manifestRenames.map { case (key, clazz) => descriptorRewrite.findFirstMatchIn(key).get.group(1) -> clazz.getSimpleName.replace("$", "") }
}
