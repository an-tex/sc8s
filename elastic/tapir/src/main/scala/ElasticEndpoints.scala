package net.sc8s.elastic.tapir

import akka.actor.typed.ActorSystem
import cats.implicits.catsSyntaxEitherId
import net.sc8s.elastic.Evolver
import sttp.tapir._
import sttp.tapir.server.ServerEndpoint

import scala.concurrent.Future

class ElasticEndpoints(
                        evolver: Evolver.Wiring,
                        actorSystem: ActorSystem[_],
                      ) {
  import actorSystem.executionContext

  private val migrateIndices =
    endpoint
      .post
      .in("elastic" / "index" / "migrate")
      .in(query[List[String]]("indices").default(Nil))
      .in(query[Option[Boolean]]("forceReindex"))

  private val cancelIndicesMigration =
    endpoint
      .post
      .in("elastic" / "index" / "migrate" / "cancel")

  private val evolveDocuments =
    endpoint
      .post
      .in("elastic" / "documents" / "evolve")
      .in(query[List[String]]("indices").default(Nil))

  private val batchUpdate =
    endpoint
      .post
      .in("elastic" / "documents" / "batch-update")
      .in(query[String]("index"))
      .in(query[String]("job"))

  val endpoints: Seq[Endpoint[_, _, _, _, _]] = Seq(
    migrateIndices,
    cancelIndicesMigration,
    evolveDocuments,
    batchUpdate,
  )

  val serverEndpoints: Seq[ServerEndpoint[Any, Future]] = Seq(
    migrateIndices.serverLogic[Future] { case (indices, forceReindex) =>
      evolver.actorRef ! Evolver.Command.MigrateIndices(indices, forceReindex.getOrElse(false))
      Future.successful(().asRight)
    },
    cancelIndicesMigration.serverLogic[Future] { _ =>
      evolver.actorRef ! Evolver.Command.CancelIndicesMigration
      Future.successful(().asRight)
    },
    evolveDocuments.serverLogic[Future] { indices =>
      evolver.actorRef ! Evolver.Command.EvolveDocuments(indices)
      Future.successful(().asRight)
    },
    batchUpdate.serverLogic[Future] { case (index, job) =>
      evolver.actorRef ! Evolver.Command.RunBatchUpdates(index, job)
      Future.successful(().asRight)
    },
  )
}

trait ElasticEndpointsComponents {
  val evolver: Evolver.Wiring
  implicit val actorSystemTyped: ActorSystem[_]

  lazy val elasticEndpoints = new ElasticEndpoints(evolver, actorSystemTyped)
}
