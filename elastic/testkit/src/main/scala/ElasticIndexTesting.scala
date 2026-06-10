package net.sc8s.elastic.testkit

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import cats.implicits.{catsStdInstancesForFuture, toTraverseOps}
import com.sksamuel.elastic4s.{ElasticClient, ElasticProperties}
import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.http.JavaClient
import com.sksamuel.elastic4s.requests.mappings.MappingDefinition
import net.sc8s.elastic.{Index, IndexSetup}
import org.scalatest.Inspectors.forAll
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, EitherValues, Suite}

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Random

trait ElasticIndexTesting extends BeforeAndAfterEach with BeforeAndAfterAll with EitherValues {
  self: Suite with ScalaTestWithActorTestKit =>

  val elasticIndices: Set[Index]

  implicit lazy val elasticClient: ElasticClient[Future] = {
    implicit val executionContext: ExecutionContextExecutor = system.executionContext
    ElasticClient(JavaClient(ElasticProperties("http://localhost:9200")))
  }

  implicit lazy val indexSetup: IndexSetup = IndexSetup(
    elasticClient,
    system,
    Some(s"test-${Random.alphanumeric.take(8).mkString.toLowerCase}_").filter(_ => createTemporaryIndices),
    refreshImmediately = true
  )

  // set to false when e.g. querying non-local env
  val createTemporaryIndices = true

  // set to true to recreate indices instead of just deleting documents, which is faster but might lead to changing scores
  // note: refresh,forceMerge didn't help
  val recreateIndices = false

  override protected def beforeAll(): Unit = {
    if (createTemporaryIndices && !recreateIndices) {
      implicit val executionContext: ExecutionContextExecutor = system.executionContext
      forAll(elasticIndices.map(index =>
        elasticClient.execute(
          createIndex(index.name)
            .mapping(MappingDefinition(properties = index.mappings))
            .analysis(index.analysis)
        )).toList.sequence.futureValue
      )(_.result.acknowledged shouldBe true)
    }
    super.beforeAll()
  }

  override protected def beforeEach(): Unit = {
    if (createTemporaryIndices) {
      implicit val executionContext: ExecutionContextExecutor = system.executionContext
      if (recreateIndices)
        forAll(
          elasticIndices.map(recreateIndex).toList.sequence.futureValue
        )(
          _.result.acknowledged shouldBe true
        )
      else
        forAll(
          elasticIndices.map(_.deleteAll()).toList.sequence.futureValue
        )(
          _.isLeft shouldBe true
        )
    }
    super.beforeEach()
  }

  protected override def afterAll(): Unit = {
    if (createTemporaryIndices)
      elasticClient.execute(deleteIndex(elasticIndices.map(_.name)))
    super.afterAll()
  }

  def recreateIndex(index: Index) = {
    elasticClient.execute(deleteIndex(index.name)).futureValue
    elasticClient.execute(
      createIndex(index.name)
        .mapping(MappingDefinition(properties = index.mappings))
        .analysis(index.analysis)
    )
  }
}
