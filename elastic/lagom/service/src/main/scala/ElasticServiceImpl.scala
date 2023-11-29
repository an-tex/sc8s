package net.sc8s.elastic.lagom

import akka.NotUsed
import com.lightbend.lagom.scaladsl.api.ServiceCall
import net.sc8s.elastic.Evolver
import net.sc8s.elastic.lagom.api.ElasticService

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

  override def cancelIndicesMigration = ServiceCall { _ =>
    evolver.actorRef ! Evolver.Command.CancelIndicesMigration
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
