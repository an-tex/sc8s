package net.sc8s.akka.components.persistence.projection.tapir

import akka.actor.typed.ActorSystem
import cats.implicits.catsSyntaxEitherId
import net.sc8s.akka.components.persistence.projection.ManagedProjection
import net.sc8s.akka.components.persistence.projection.api.ProjectionService.ProjectionsStatus
import net.sc8s.akka.components.persistence.projection.common.ProjectionManagement
import sttp.tapir.EndpointIO.Example
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.ServerEndpoint

import scala.concurrent.Future

class ClusterComponentsPersistenceProjectionEndpoints(
                                                       projections: Set[ManagedProjection[_]],
                                                       actorSystem: ActorSystem[_],
                                                     ) {

  private lazy val projectionManagement = new ProjectionManagement(projections, actorSystem)

  private lazy val projectionNameExamples = projections.map(p => Example.of(p.projectionName, Some(p.projectionName))).toList

  import actorSystem.executionContext

  private val projectionPath = "projection" / path[String]("projectionName").examples(projectionNameExamples)

  private val rebuildProjection =
    endpoint
      .post
      .in(projectionPath / "rebuild")
      .errorOut(stringBody)

  private val pauseProjection =
    endpoint
      .post
      .in(projectionPath / "pause")
      .errorOut(stringBody)

  private val resumeProjection =
    endpoint
      .post
      .in(projectionPath / "resume")
      .errorOut(stringBody)

  private val projectionStatus =
    endpoint
      .get
      .in(projectionPath)
      .out(jsonBody[ProjectionsStatus])
      .errorOut(stringBody)

  private val projectionsStatus =
    endpoint
      .get
      .in("projection")
      .out(jsonBody[List[ProjectionsStatus]])

  val endpoints: Seq[Endpoint[_, _, _, _, _]] = Seq(
    rebuildProjection,
    pauseProjection,
    resumeProjection,
    projectionStatus,
    projectionsStatus,
  )

  val serverEndpoints: Seq[ServerEndpoint[Any, Future]] = Seq(
    rebuildProjection.serverLogic[Future](projectionManagement.rebuildProjection(_).map(_.map(_ => ()))),
    pauseProjection.serverLogic[Future](projectionManagement.pauseProjection(_).map(_.map(_ => ()))),
    resumeProjection.serverLogic[Future](projectionManagement.resumeProjection(_).map(_.map(_ => ()))),
    projectionStatus.serverLogic[Future](projectionManagement.projectionStatus),
    projectionsStatus.serverLogic[Future](_ => projectionManagement.projectionsStatus.map(_.asRight)),
  )
}
