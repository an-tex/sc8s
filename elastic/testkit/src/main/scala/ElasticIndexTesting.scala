package net.sc8s.elastic.testkit

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import cats.implicits.toTraverseOps
import com.sksamuel.elastic4s.ElasticClient
import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.akka.{AkkaHttpClient, AkkaHttpClientSettings}
import com.sksamuel.elastic4s.requests.mappings.MappingDefinition
import net.sc8s.elastic.{Index, IndexSetup}
import org.scalatest.Inspectors.forAll
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, EitherValues, Suite}

import scala.util.Random

trait ElasticIndexTesting extends BeforeAndAfterEach with BeforeAndAfterAll with EitherValues {
  _: Suite with ScalaTestWithActorTestKit =>

  val elasticIndices: Set[Index]

  implicit lazy val elasticClient = ElasticClient(AkkaHttpClient(AkkaHttpClientSettings(Seq("localhost:9210")))(system.toClassic))

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

  override protected def beforeAll() = {
    if (createTemporaryIndices && !recreateIndices) {
      implicit val executionContext = system.executionContext
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

  override protected def beforeEach() = {
    if (createTemporaryIndices) {
      implicit val executionContext = system.executionContext
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

  protected override def afterAll() = {
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
