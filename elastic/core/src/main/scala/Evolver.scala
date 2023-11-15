package net.sc8s.elastic

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.stream.scaladsl.{Sink, Source}
import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.fields.{ElasticField, KeywordField}
import com.sksamuel.elastic4s.requests.mappings.MappingDefinition
import com.sksamuel.elastic4s.requests.task.GetTaskResponse
import com.sksamuel.elastic4s.streams.ReactiveElastic._
import com.sksamuel.elastic4s.{ElasticClient, RequestFailure, RequestSuccess}
import io.circe.Codec
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import net.sc8s.akka.circe.CirceSerializer
import net.sc8s.akka.components.ClusterComponent
import net.sc8s.akka.stream.RateLogger
import net.sc8s.circe.CodecConfiguration._
import net.sc8s.elastic.Evolver.Command.{AddMappings, AliasUpdated, BatchUpdatesFinished, CheckTaskCompletion, DocumentsEvolved, EvolveDocuments, EvolveNextIndex, IndexMigrated, IndexMigrationFailed, IndexMigrationStarted, IndexSettingsUpdated, MappingsAdded, MigrateIndex, MigrateIndices, MigrateNextIndex, OldIndexDeleted, RunBatchUpdates, TaskStatus, UpdateIndexSettings}
import net.sc8s.logstage.elastic.Logging.IzLoggerTags

import java.time.LocalDateTime
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object Evolver extends ClusterComponent.Singleton {
  sealed trait Command
  sealed trait SerializableCommand extends Command
  object Command {
    case class MigrateIndices(indices: Seq[String], forceReindex: Boolean) extends SerializableCommand
    case class EvolveDocuments(indices: Seq[String]) extends SerializableCommand
    case class RunBatchUpdates(index: String, job: String) extends SerializableCommand

    private[Evolver] case class MigrateNextIndex(pendingIndices: Seq[Index]) extends Command
    private[Evolver] case class MigrateIndex(index: Index, oldIndexName: String, newIndexName: String, pendingIndices: Seq[Index]) extends Command

    private[Evolver] case class UpdateIndexSettings(index: Index, pendingIndices: Seq[Index]) extends Command
    private[Evolver] case class IndexSettingsUpdated(result: Try[Done]) extends Command

    private[Evolver] case class AddMappings(index: Index, mappings: Seq[ElasticField], pendingIndices: Seq[Index]) extends Command
    private[Evolver] case class MappingsAdded(result: Try[Done]) extends Command

    private[Evolver] case class IndexMigrationStarted(nodeId: String, taskId: String) extends Command
    private[Evolver] case class CheckTaskCompletion(nodeId: String, taskId: String) extends Command
    private[Evolver] case class TaskStatus(status: Try[GetTaskResponse]) extends Command
    private[Evolver] case object IndexMigrated extends Command
    private[Evolver] case class IndexMigrationFailed(index: Index, exception: Throwable) extends Command
    private[Evolver] case class AliasUpdated(index: Try[Done]) extends Command
    private[Evolver] case class OldIndexDeleted(deleted: Try[Done]) extends Command

    private[Evolver] case object EvolveNextIndex extends Command
    private[Evolver] case class DocumentsEvolved(index: Index, result: Try[Seq[Index]]) extends Command
    private[Evolver] case class BatchUpdatesFinished(result: Try[Done]) extends Command

    implicit val codec: Codec[SerializableCommand] = deriveConfiguredCodec
  }

  class Component(
                   elasticClient: ElasticClient,
                   elasticIndices: Set[Index]
                 ) extends BaseComponent {

    override val behavior = { ctx =>
      import ctx.{log, materializer, actorContext => context}
      import context.executionContext
      implicit val classicActorSystem = context.system.toClassic

      def resolveElasticIndices(indices: Seq[String]) =
        if (indices.nonEmpty) indices.map(index => elasticIndices.find(_.name == index).get)
        else elasticIndices.toSeq

      def idle = Behaviors.receiveMessagePartial[Command] {
        case MigrateIndices(indices, forceReindex) =>
          val indicesToMigrate = resolveElasticIndices(indices)
          log.info(s"${"startingIndicesMigration" -> "tag"} for ${indicesToMigrate.map(_.name) -> "indices"}")
          context.self ! MigrateNextIndex(indicesToMigrate)
          migratingIndices(forceReindex)

        case EvolveDocuments(indices) =>
          val indicesToMigrate = resolveElasticIndices(indices)
          log.info(s"${"startingIndicesEvolution" -> "tag"} for ${indicesToMigrate.map(_.name) -> "indices"}")
          context.self ! EvolveNextIndex
          evolvingDocuments(indicesToMigrate)

        case RunBatchUpdates(index, batchUpdateName) =>
          resolveElasticIndices(Seq(index)).headOption match {
            case Some(elasticIndex) =>
              elasticIndex.batchUpdates.find(_.job == batchUpdateName) match {
                case Some(batchUpdate) =>
                  val batchUpdates = elasticClient.execute(count(elasticIndex.name)).flatMap { countResponse =>
                    val count = countResponse.result.count

                    import elasticIndex.{latestTraitIndexable, versionedHitReader}

                    Source
                      .fromPublisher(elasticClient.publisher(search(elasticIndex.name) keepAlive "1m"))
                      .via(RateLogger(s"runningBatchUpdates|${elasticIndex.name}|$batchUpdateName", total = Some(count -> count)))
                      .groupedWithin(1000, 3.seconds)
                      .mapAsyncUnordered(8) { hits =>
                        val bulkRequest = hits.map(hit =>
                          // use indexInto instead of update to remove removed fields
                          indexInto(elasticIndex.name) id hit.id source batchUpdate.update(hit.to[elasticIndex.Latest])
                        )
                        elasticClient.execute(bulk(bulkRequest))
                      }
                      .map(response => response.result.failures.toList match {
                        case firstError :: _ =>
                          throw new Exception(s"some bulk inserts failed, first failure: $firstError")
                        case Nil =>
                          response.result.successes.length
                      })
                      .runWith(Sink.ignore)
                  }

                  context.pipeToSelf(batchUpdates)(BatchUpdatesFinished)
                  runningBatchUpdates(elasticIndex, batchUpdateName)

                case None =>
                  log.error(s"${"batchUpdateNotFound" -> "tag"} on ${elasticIndex.name -> "index"} $batchUpdateName")
                  Behaviors.same
              }
            case None =>
              log.error(s"${"indexNotFound" -> "tag"} $index $batchUpdateName")
              Behaviors.same
          }
      }

      def runningBatchUpdates(index: Index, batchUpdate: String): Behaviors.Receive[Command] = Behaviors.receiveMessagePartial {
        case BatchUpdatesFinished(Success(_)) =>
          log.info(s"${"batchUpdatesFinished" -> "tag"} of ${index.name -> "index"} for $batchUpdate")
          idle

        case BatchUpdatesFinished(Failure(exception)) =>
          log.info(s"${"batchUpdatesFailed" -> "tag"} of ${index.name -> "index"} for $batchUpdate with $exception")
          idle
      }

      def migratingIndices(forceReindex: Boolean): Behaviors.Receive[Command] = {
        def createIndexWithMappings(index: Index, indexName: String) = {
          createIndex(indexName)
            .mapping(
              MappingDefinition(meta = Map(
                mappingsHashField -> index.mappingsHash,
                analysisHashField -> index.analysisHash,
                settingsHashField -> index.settingsHash,
              ), properties = index.mappings :+ KeywordField(Index.discriminator))
            )
            .analysis(index.analysis)
            .settings(index.settings)
        }

        def default: Behaviors.Receive[Command] = Behaviors.receiveMessagePartial {
          case MigrateNextIndex(pendingIndices) =>
            pendingIndices.toList match {
              case Nil =>
                log.info(s"${"allIndicesMigrated" -> "tag"}")
                idle
              case index :: updatedPendingIndices =>
                val newIndexName = s"${index.name}-${LocalDateTime.now.format(Index.indexNameSuffixFormatter)}"

                val eventualCommand = elasticClient.execute(getIndex(index.name)).flatMap {
                  case RequestFailure(_, _, _, error) =>
                    if (error.`type` != "index_not_found_exception") Future.failed(error.asException)
                    else {
                      log.info(s"${"create" -> "tag"} ${index.name -> "index"}")
                      for {
                        _ <- elasticClient.execute(createIndexWithMappings(index, newIndexName)).map(_.result)
                        _ <- elasticClient.execute(addAlias(index.name, newIndexName)).map(_.result)
                      } yield MigrateNextIndex(updatedPendingIndices)
                    }

                  case RequestSuccess(_, _, _, result) =>
                    val (existingIndexOriginalName, existingIndex) = result.head

                    lazy val migrateIndex = Future.successful(MigrateIndex(index, existingIndexOriginalName, newIndexName, updatedPendingIndices))
                    val maybeHashes = for {
                      analysisHash <- existingIndex.mappings.meta.get(analysisHashField)
                      mappingsHash <- existingIndex.mappings.meta.get(mappingsHashField)
                      settingsHash <- existingIndex.mappings.meta.get(settingsHashField)
                    } yield (analysisHash, mappingsHash, settingsHash)

                    maybeHashes match {
                      case _ if forceReindex =>
                        log.warn(s"${"forcingReindex" -> "tag"} of ${index.name -> "index"}")
                        migrateIndex

                      case Some((existingAnalysisHash, existingMappingsHash, existingSettingsHash)) =>
                        // it's possible but hard to check whether mappings have only been added (in which case a reindex would not be necessary), or existing have changed. we just take the easy route...
                        if (existingAnalysisHash != index.analysisHash || existingMappingsHash != index.mappingsHash)
                          migrateIndex
                        else if (existingSettingsHash != index.settingsHash)
                          Future.successful(UpdateIndexSettings(index, updatedPendingIndices))
                        else {
                          log.info(s"${"skippingMigration" -> "tag"} of ${index.name -> "index"}")
                          Future.successful(MigrateNextIndex(updatedPendingIndices))
                        }

                      case None =>
                        log.warn(s"${"missingMetaData" -> "tag"} in ${index.name -> "index"}, initial migration?")
                        migrateIndex
                    }
                }

                context.pipeToSelf(eventualCommand)(_.fold(IndexMigrationFailed(index, _), identity))
                Behaviors.same
            }

          // not used any more as we do a full migration in any case
          case AddMappings(index, mappings, pendingIndices) =>
            log.info(s"${"addingMappings" -> "tag"} to ${index.name -> "index"} $mappings")
            context.pipeToSelf(
              elasticClient.execute(putMapping(index.name) properties mappings meta Map(mappingsHashField -> index.mappingsHash)).map(_.result).map(_ => Done)
            )(triedDone => MappingsAdded(triedDone))
            addingMappings(index, pendingIndices)

          case UpdateIndexSettings(index, pendingIndices) =>
            log.infoT("updatingSettings", s"${index.name -> "index"} ${index.settings}")
            context.pipeToSelf(
              elasticClient.execute(updateSettings(index.name, index.settings.view.mapValues(_.toString).toMap)).map(_.result).map(_ => Done)
            )(triedDone => IndexSettingsUpdated(triedDone))
            updatingIndexSettings(index, pendingIndices)

          case MigrateIndex(index, oldIndexName, newIndexName, pendingIndices) =>
            log.info(s"${"migratingIndex" -> "tag"} ${index.name -> "index"} from $oldIndexName to $newIndexName")
            val eventualCommand = for {
              _ <- elasticClient.execute(createIndexWithMappings(index, newIndexName)).map(_.result)
              result <- elasticClient.execute(reindex(oldIndexName, newIndexName) waitForCompletion false shouldStoreResult true).map(_.result)
            } yield result match {
              case Right(createTaskResponse) =>
                IndexMigrationStarted(createTaskResponse.nodeId, createTaskResponse.taskId)

              // this case should not be called due to waitForCompletion=false
              case Left(reindexResponse) =>
                log.info(s"${"indexMigrationFinished" -> "tag"} with ${reindexResponse -> "stats"}")
                IndexMigrated
            }

            context.pipeToSelf(eventualCommand)(_.fold(e => IndexMigrationFailed(index, e), identity))

            migratingIndex(index, oldIndexName, newIndexName, pendingIndices)

          case IndexMigrationFailed(index, exception) =>
            log.error(s"${"indexMigrationFailed" -> "tag"} of ${index.name -> "index"} with $exception")
            idle
        }

        def addingMappings(index: Index, pendingIndices: Seq[Index]) = Behaviors.receiveMessagePartial[Command] {
          case MappingsAdded(Success(_)) =>
            log.info(s"${"mappingsAdded" -> "tag"} to ${index.name -> "index"}")
            context.self ! MigrateNextIndex(pendingIndices)
            migratingIndices(forceReindex)

          case MappingsAdded(Failure(exception)) =>
            log.info(s"${"addingMappingsFailed" -> "tag"} to ${index.name -> "index"} with $exception, aborting")
            idle
        }

        def updatingIndexSettings(index: Index, pendingIndices: Seq[Index]) = Behaviors.receiveMessagePartial[Command] {
          case IndexSettingsUpdated(Success(_)) =>
            log.infoT("indexSettingsUpdated", s"${index.name -> "index"}")
            context.self ! MigrateNextIndex(pendingIndices)
            migratingIndices(forceReindex)

          case IndexSettingsUpdated(Failure(exception)) =>
            log.errorT("updatingIndexSettingsFailed", s"${index.name -> "index"} with $exception, aborting")
            idle
        }

        def migratingIndex(index: Index, oldIndexName: String, newIndexName: String, pendingIndices: Seq[Index]) = Behaviors.withTimers[Command](timerScheduler => Behaviors.receiveMessagePartial {
          case IndexMigrationStarted(nodeId, taskId) =>
            log.infoT("indexMigrationQueued", s"of ${index.name -> "index"} at $nodeId with $taskId")
            timerScheduler.startTimerWithFixedDelay("taskCheck", CheckTaskCompletion(nodeId, taskId), 1.second)
            Behaviors.same

          case IndexMigrationFailed(index, exception) =>
            timerScheduler.cancelAll()
            log.errorT("indexMigrationFailed", s"of ${index.name -> "index"} with $exception")
            idle

          case CheckTaskCompletion(nodeId, taskId) =>
            val eventualTaskResponse = elasticClient.execute(getTask(nodeId, taskId)).map(_.result)
            context.pipeToSelf(eventualTaskResponse)(TaskStatus)
            Behaviors.same

          case TaskStatus(Success(getTaskResponse)) =>
            val status = getTaskResponse.task.status
            val total = status.total
            val left = status.total - status.created
            val percent = ((status.created.toDouble / total) * 100).toLong
            val rate = status.created / Math.max(1, getTaskResponse.task.runningTime.toSeconds)
            val secondsRemaining = left / Math.max(1, rate)

            log.infoT("indexMigrationStatus", s"of ${index.name -> "index"} $total $left $rate/s $secondsRemaining ${percent -> "done"}%")
            getTaskResponse.error match {
              case Some(error) =>
                log.errorT("taskFailed", s"of ${index.name -> "index"} with $error, aborting")
                idle
              case None =>
                if (getTaskResponse.completed) context.self ! IndexMigrated
                Behaviors.same
            }

          case TaskStatus(Failure(exception)) =>
            timerScheduler.cancelAll()
            log.error(s"${"getTaskStatusFailed" -> "tag"} of ${index.name -> "index"} with $exception, aborting")
            idle

          case IndexMigrated =>
            timerScheduler.cancelAll()

            log.info(s"${"indexMigrated" -> "tag"} ${index.name -> "index"} from $oldIndexName to $newIndexName")

            val aliasUpdated = for {
              _ <- elasticClient.execute(addAlias(index.name, newIndexName)).map(_.result)
              _ <- elasticClient.execute(removeAlias(index.name, oldIndexName)).map(_.result)
            } yield Done

            context.pipeToSelf(aliasUpdated)(AliasUpdated)
            Behaviors.same

          case AliasUpdated(Success(_)) =>
            log.info(s"${"aliasUpdated" -> "tag"} of ${index.name -> "index"} from $oldIndexName to $newIndexName")
            context.pipeToSelf(elasticClient.execute(deleteIndex(oldIndexName)).map(_.result).map(_ => Done))(OldIndexDeleted)
            Behaviors.same

          case AliasUpdated(Failure(exception)) =>
            log.error(s"${"aliasUpdateFailed" -> "tag"} of ${index.name -> "index"} from $oldIndexName to $newIndexName with $exception, aborting")
            idle

          case OldIndexDeleted(Success(_)) =>
            log.info(s"${"oldIndexDeleted" -> "tag"} of ${index.name -> "index"} $oldIndexName")
            context.self ! MigrateNextIndex(pendingIndices)
            migratingIndices(forceReindex)

          case OldIndexDeleted(Failure(exception)) =>
            log.error(s"${"deletingOldIndexFailed" -> "tag"} of ${index.name -> "index"} $oldIndexName with $exception, aborting")
            idle
        })

        default
      }

      def evolvingDocuments(pendingIndices: Seq[Index]): Behaviors.Receive[Command] = Behaviors.receiveMessagePartial {
        case EvolveNextIndex =>
          pendingIndices.toList match {
            case Nil =>
              log.info(s"${"allIndicesEvolved" -> "tag"}")
              idle

            case index :: updatedPendingIndices =>
              val counts = for {
                needEvolution <- elasticClient.execute(count(index.name) query not(termQuery(Index.discriminator, index.latestVersion)))
                alreadyEvolved <- elasticClient.execute(count(index.name) query termQuery(Index.discriminator, index.latestVersion))
              } yield needEvolution -> alreadyEvolved

              val indexEvolved = counts.flatMap {
                case (needEvolutionCountResponse, alreadyEvolvedCountResponse) =>
                  val documentsToEvolve = needEvolutionCountResponse.result.count
                  val alreadyEvolvedDocuments = alreadyEvolvedCountResponse.result.count

                  if (documentsToEvolve > 0) {
                    log.info(s"${"evolvingDocuments" -> "tag"} of ${index.name -> "index"} with $documentsToEvolve to ${index.latestVersion -> "latestDocumentVersion"} having $alreadyEvolvedDocuments")
                    import index.{latestTraitIndexable, versionedHitReader}

                    Source
                      .fromPublisher(elasticClient.publisher(search(index.name) query not(termQuery(Index.discriminator, index.latestVersion)) keepAlive "1m"))
                      .via(RateLogger(s"evolvingDocuments|${index.name}", total = Some(documentsToEvolve -> (documentsToEvolve + alreadyEvolvedDocuments))))
                      .groupedWithin(1000, 3.seconds)
                      .mapAsyncUnordered(8) { hits =>
                        val bulkRequest = hits.map(hit =>
                          // use indexInto instead of update to remove removed fields
                          indexInto(index.name) id hit.id source hit.to[index.Latest]
                        )
                        elasticClient.execute(bulk(bulkRequest))
                      }
                      .map(response => response.result.failures.toList match {
                        case firstError :: _ =>
                          throw new Exception(s"some bulk inserts failed, first failure: $firstError")
                        case Nil =>
                          response.result.successes.length
                      }
                      )
                      .runWith(Sink.ignore)
                  } else {
                    log.info(s"${"skippingDocumentsEvolution" -> "tag"} of ${index.name -> "index"} with $alreadyEvolvedDocuments at ${index.latestVersion -> "latestDocumentVersion"}")
                    Future.successful(Done)
                  }
              }.map(_ => updatedPendingIndices)
              context.pipeToSelf(indexEvolved)(tried => DocumentsEvolved(index, tried))
              Behaviors.same
          }

        case DocumentsEvolved(index, Success(pendingIndices)) =>
          log.info(s"${"indexEvolved" -> "tag"} of ${index.name -> "index"}")

          context.self ! EvolveNextIndex
          evolvingDocuments(pendingIndices)

        case DocumentsEvolved(index, Failure(exception)) =>
          log.error(s"${"indexEvolutionFailed" -> "tag"} of ${index.name -> "index"} with $exception, aborting")
          idle
      }

      context.self ! MigrateIndices(Nil, forceReindex = false)

      idle
    }
  }

  private[elastic] val mappingsHashField = "mappingHash"

  private val analysisHashField = "analysisHash"

  private val settingsHashField = "settingsHash"

  override val name = "elastic-evolver"

  override val commandSerializer = CirceSerializer()
}
