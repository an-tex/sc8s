package net.sc8s.akka.components.persistence.projection.api

import io.circe.Codec
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import net.sc8s.circe.CodecConfiguration._

object ProjectionService {
  sealed trait ProjectionStatus {
    val last10Errors: Seq[(Long, String)]
  }

  object ProjectionStatus {
    case object Initializing extends ProjectionStatus {
      override val last10Errors: Seq[(Long, ProjectionId)] = Nil
    }
    case class Running(sequenceNr: Option[Long], last10Errors: Seq[(Long, String)]) extends ProjectionStatus
    case class Stopped(previousProjectionStatus: ProjectionStatus, last10Errors: Seq[(Long, String)]) extends ProjectionStatus
    case class Failed(cause: String, last10Errors: Seq[(Long, String)]) extends ProjectionStatus

    implicit val codec: Codec[ProjectionStatus] = {
      import io.circe.generic.extras.auto._
      deriveConfiguredCodec
    }
  }

  type ProjectionId = String
  case class ProjectionsStatus(projectionName: String, keysStatus: Map[ProjectionId, ProjectionStatus])
  object ProjectionsStatus {
    implicit val codec: Codec[ProjectionsStatus] = deriveConfiguredCodec
  }
}
