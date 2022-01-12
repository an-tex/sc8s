package net.sc8s.akka.circe

import io.circe.Codec

import scala.reflect.ClassTag

// don't make it a case class as type, implicit parameters or field members aren't considered in equals/hashCode, leading to issues with e.g. Sets
class CirceSerializer[T] private(
                                  val manifestRenames: Map[String, Class[_]],
                                  val migrate: PartialFunction[T, T]
                                )(implicit classTag: ClassTag[T], val codec: Codec[T]) {
  val entityClass: Class[T] = classTag.runtimeClass.asInstanceOf[Class[T]]

  private val descriptorRewrite = ".*[$.]([^$]+)[$]?$".r

  val descriptorRenames = manifestRenames.map { case (key, clazz) => descriptorRewrite.findFirstMatchIn(key).get.group(1) -> clazz.getSimpleName.replace("$", "") }

  def canEqual(a: Any) = a.isInstanceOf[CirceSerializer[_]]

  override def equals(that: Any): Boolean =
    that match {
      case that: CirceSerializer[_] => that.canEqual(this) && this.hashCode == that.hashCode
      case _ => false
    }

  override def hashCode: Int = {
    val prime = 31
    var result = 1
    result = prime * result + manifestRenames.hashCode();
    result = prime * result + migrate.hashCode()
    result = prime * result + classTag.hashCode()
    result = prime * result + codec.hashCode()
    result
  }
}

object CirceSerializer {
  def apply[T](
                manifestRenames: Map[String, Class[_]] = Map.empty[String, Class[_]],
                migrate: PartialFunction[T, T] = PartialFunction.empty
              )(implicit classTag: ClassTag[T], codec: Codec[T]) = new CirceSerializer[T](manifestRenames, migrate)
}


