package net.sc8s.akka.projection.api

import io.circe.Codec
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import net.sc8s.circe.CodecConfiguration._

object ProjectionService {
  sealed trait ProjectionStatus
  object ProjectionStatus {
    case object Initializing extends ProjectionStatus
    case class Running(sequenceNr: Option[Long], last10Errors: Seq[(Long, String)]) extends ProjectionStatus
    case class Stopped(previousProjectionStatus: ProjectionStatus) extends ProjectionStatus
    case class Failed(cause: String) extends ProjectionStatus

    implicit val codec: Codec[ProjectionStatus] = {
      import io.circe.generic.extras.auto._
      deriveConfiguredCodec
    }
  }

  case class ProjectionsStatus(projectionName: String, keysStatus: Map[String, ProjectionStatus])
  object ProjectionsStatus {
    implicit val codec: Codec[ProjectionsStatus] = deriveConfiguredCodec
  }
}
