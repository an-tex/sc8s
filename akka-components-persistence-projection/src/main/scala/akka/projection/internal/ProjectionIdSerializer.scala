package akka.projection.internal

import akka.annotation.InternalApi
import akka.projection.ProjectionId
import akka.projection.internal.protobuf.ProjectionMessages
import akka.serialization.{BaseSerializer, SerializerWithStringManifest}

import java.io.NotSerializableException

/**
 * INTERNAL API
 */
@InternalApi private[projection] class ProjectionIdSerializer(val system: akka.actor.ExtendedActorSystem)
  extends SerializerWithStringManifest
    with BaseSerializer {

  private val ProjectionIdManifest = "a1"

  override def manifest(o: AnyRef): String = o match {
    case _: ProjectionId => ProjectionIdManifest
    case _ =>
      throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass} in [${getClass.getName}]")
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case m: ProjectionId => projectionIdToBinary(m)
    case _ =>
      throw new IllegalArgumentException(s"Cannot serialize object of type [${o.getClass.getName}]")
  }

  private def projectionIdToBinary(m: ProjectionId): Array[Byte] = {
    projectionIdToProto(m).toByteArray
  }

  private def projectionIdToProto(projectionId: ProjectionId): ProjectionMessages.ProjectionId = {
    ProjectionMessages.ProjectionId.newBuilder().setName(projectionId.name).setKey(projectionId.key).build()
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case ProjectionIdManifest => projectionIdFromBinary(bytes)
    case _ =>
      throw new NotSerializableException(
        s"Unimplemented deserialization of message with manifest [$manifest] in [${getClass.getName}]")
  }

  private def projectionIdFromBinary(bytes: Array[Byte]): AnyRef = {
    val projectionId = ProjectionMessages.ProjectionId.parseFrom(bytes)
    projectionIdFromProto(projectionId)
  }

  private def projectionIdFromProto(p: ProjectionMessages.ProjectionId): ProjectionId =
    ProjectionId(p.getName, p.getKey)
}
