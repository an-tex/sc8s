package mu.moin.elastic

import akka.NotUsed
import com.lightbend.lagom.scaladsl.api.ServiceCall
import mu.moin.elastic.api.ElasticService
import mu.moin.elastic.evolution.Evolver

import scala.concurrent.Future

trait ElasticServiceImpl extends ElasticService {
  val evolver: Evolver.Wiring

  override def migrateIndices(indices: Seq[String], forceReindex: Option[Boolean]) = ServiceCall { _ =>
    evolver.actorRef ! Evolver.Command.MigrateIndices(
      indices,
      forceReindex.getOrElse(false)
    )
    Future.successful(NotUsed)
  }

  override def evolveDocuments(indices: Seq[String]) = ServiceCall { _ =>
    evolver.actorRef ! Evolver.Command.EvolveDocuments(indices)
    Future.successful(NotUsed)
  }

  override def batchUpdate(index: String, job: String) = ServiceCall { _ =>
    evolver.actorRef ! Evolver.Command.RunBatchUpdates(index, job)
    Future.successful(NotUsed)
  }
}