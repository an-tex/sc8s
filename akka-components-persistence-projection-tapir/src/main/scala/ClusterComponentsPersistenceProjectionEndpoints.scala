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

  private[this] lazy val projectionManagement = new ProjectionManagement(projections, actorSystem)

  private[this] lazy val projectionNameExamples = projections.map(p => Example.of(p.projectionName, Some(p.projectionName))).toList

  import actorSystem.executionContext

  private[this] val rebuildProjection =
    endpoint
      .post
      .in("projection" / path[String]("projectionName").examples(projectionNameExamples) / "rebuild")

  private[this] val pauseProjection =
    endpoint
      .post
      .in("projection" / path[String]("projectionName").examples(projectionNameExamples) / "pause")

  private[this] val resumeProjection =
    endpoint
      .post
      .in("projection" / path[String]("projectionName").examples(projectionNameExamples) / "resume")

  private[this] val projectionStatus =
    endpoint
      .get
      .in("projection" / path[String]("projectionName").examples(projectionNameExamples))
      .out(jsonBody[ProjectionsStatus])

  private[this] val projectionsStatus =
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
    rebuildProjection.serverLogic[Future](projectionManagement.rebuildProjection(_).map(_ => ().asRight)),
    pauseProjection.serverLogic[Future](projectionManagement.pauseProjection(_).map(_ => ().asRight)),
    resumeProjection.serverLogic[Future](projectionManagement.resumeProjection(_).map(_ => ().asRight)),
    projectionStatus.serverLogic[Future](projectionManagement.projectionStatus(_).map(_.asRight)),
    projectionsStatus.serverLogic[Future](_ => projectionManagement.projectionsStatus.map(_.asRight)),
  )
}
