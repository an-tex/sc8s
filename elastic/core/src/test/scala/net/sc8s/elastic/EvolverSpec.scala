//package net.sc8s.elastic

// TODO disabled for now as it sometimes fails in gitlab without any error, issue with mixing scalatest and specs? prolly should be moved into separate repo anyway...
/*
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.actor.ActorSystem
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.adapter._
import com.sksamuel.elastic4s.ElasticClient
import com.sksamuel.elastic4s.ElasticDsl.{RichFuture => _, _}
import com.sksamuel.elastic4s.akka.{AkkaHttpClient, AkkaHttpClientSettings}
import com.sksamuel.elastic4s.circe._
import com.sksamuel.elastic4s.requests.indexes.Field
import com.sksamuel.elastic4s.requests.mappings.{FieldDefinition, KeywordField, TextField}
import com.typesafe.config.ConfigFactory
import io.circe.generic.extras.semiauto._
import io.circe.parser._
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import mu.moin.elastic.evolution.Evolver.{EvolveDocuments, MigrateIndices, RunBatchUpdates}
import mu.moin.elastic.evolution.EvolverSpec._
import mu.moin.elastic.evolution.Index.BatchUpdate
import mu.moin.schemaevolution.Schema
import org.scalatest.Inspectors._
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class EvolverSpec extends AkkaTypedSpec with Matchers with Eventually {

  private val elasticClient = ElasticClient(AkkaHttpClient(AkkaHttpClientSettings(Seq(sys.env.getOrElse("ELASTIC_URL", "localhost:9210")))))

  private val testIndices = Seq(TestIndex1, TestIndex2)

  "Evolver" should {
    "create indices, mappings and aliases" in new EvolverContext {
      evolver ! MigrateIndices(testIndices.map(_.name), forceReindex = false)

      eventually {
        val response = elasticClient.execute(catAliases()).awaitResult
        response.result.map(_.alias) should contain theSameElementsAs testIndices.map(_.name)
        forAll(response.result.map(_.index)) { index =>
          val _index :: dateTime :: Nil = index.split("-", 2).toList
          testIndices.map(_.name) should contain(_index)

          LocalDateTime.parse(dateTime, Index.indexNameSuffixFormatter).until(LocalDateTime.now, ChronoUnit.SECONDS) should be <= 10L
        }
      }

      elasticClient.execute(getIndex(TestIndex1.name)).awaitResult.result.head._2.mappings.properties shouldBe Map(
        "name" -> Field(Some("keyword")),
        "class" -> Field(Some("keyword"))
      )
    }
    "keep existing aliases and indices" in new EvolverContext {
      evolver ! MigrateIndices(testIndices.map(_.name), forceReindex = false)

      val existingAliases = eventually {
        val response = elasticClient.execute(catAliases()).awaitResult
        response.result should have length testIndices.length
        response.result
      }

      evolver ! MigrateIndices(testIndices.map(_.name), forceReindex = false)
      process()

      elasticClient.execute(catAliases()).awaitResult.result shouldBe existingAliases
    }

    def addDocumentV2() = {
      elasticClient.execute(indexInto(TestIndex1.name) source TestIndex1.DocumentV2("name1").asJson {
        import Index.configuration
        deriveConfiguredEncoder[EvolverSpec.TestIndex1.DocumentV2]
      }).awaitResult
      eventually(elasticClient.execute(count(TestIndex1.name)).awaitResult.result.count shouldBe 1)
    }

    "migrate to updated mapping by reindexing" in new EvolverContext(TestIndex1) {
      evolver ! MigrateIndices(Seq(TestIndex1.name), forceReindex = false)
      val originalIndex = eventually {
        val aliases = elasticClient.execute(catAliases()).awaitResult.result.find(_.alias == TestIndex1.name)
        aliases should not be empty
        aliases.get.index
      }
      addDocumentV2()

      spawnEvolver(TestIndex1MappingUpdated) ! MigrateIndices(Seq(TestIndex1MappingUpdated.name), forceReindex = false)

      eventually {
        elasticClient.execute(catAliases()).awaitResult.result.filter(_.alias == TestIndex1.name).map(_.index) should not contain originalIndex
        val mappings = elasticClient.execute(getIndex(TestIndex1.name)).awaitResult.result.head._2.mappings
        mappings.properties shouldBe Map("name" -> Field(Some("text")), "class" -> Field(Some("keyword")))
        mappings.meta.get(Evolver.mappingsHashField) should contain(TestIndex1MappingUpdated.mappingsHash)
        elasticClient.execute(count(TestIndex1.name)).awaitResult.result.count shouldBe 1
      }
    }
    "migrate by adding mapping" in new EvolverContext(TestIndex1) {
      evolver ! MigrateIndices(Nil, forceReindex = false)
      val originalIndex = eventually {
        val aliases = elasticClient.execute(catAliases()).awaitResult.result.find(_.alias == TestIndex1.name)
        aliases should not be empty
        aliases.get.index
      }

      addDocumentV2()

      spawnEvolver(TestIndex1MappingAdded) ! MigrateIndices(Nil, forceReindex = false)

      eventually {
        elasticClient.execute(catAliases()).awaitResult.result.filter(_.alias == TestIndex1.name).map(_.index) should contain(originalIndex)
        val mappings = elasticClient.execute(getIndex(TestIndex1.name)).awaitResult.result.head._2.mappings
        mappings.properties shouldBe Map("name" -> Field(Some("keyword")), "added" -> Field(Some("keyword")), "class" -> Field(Some("keyword")))
        mappings.meta.get(Evolver.mappingsHashField) should contain(TestIndex1MappingAdded.mappingsHash)
        elasticClient.execute(count(TestIndex1.name)).awaitResult.result.count shouldBe 1
      }
    }
    "skip migration of mappings" in new EvolverContext(TestIndex1) {
      evolver ! MigrateIndices(Nil, forceReindex = false)

      eventually(elasticClient.execute(catAliases()).awaitResult.result should not be empty)
      elasticClient.execute(putMapping(TestIndex1.name) fields Seq(KeywordField("deleted"))).awaitResult
      evolver ! MigrateIndices(Nil, forceReindex = false)
      process()

      elasticClient.execute(getIndex(TestIndex1.name)).awaitResult.result.head._2.mappings.properties === Map("name" -> Field(Some("keyword")), "deleted" -> Field(Some("keyword")))
    }
    "evolve documents with older version" in new EvolverContext(IndexV1) {
      evolver ! MigrateIndices(Nil, forceReindex = false)
      eventually(elasticClient.execute(catAliases()).awaitResult.result should not be empty)

      val document1_V1 = IndexV1.DocumentV1("first1", "last1", Some(1))
      val document2_V2 = Json.obj(
        "class" -> IndexV2.latestVersion.asJson,
        "skip this" -> "otherwise it would fail evolution".asJson
      )
      val document3_V1 = IndexV1.DocumentV1("first3", "last3", Some(3))

      elasticClient.execute(indexInto(IndexV1.name) source document1_V1.latestTrait).awaitResult
      elasticClient.execute(indexInto(IndexV1.name) source document2_V2).awaitResult
      elasticClient.execute(indexInto(IndexV1.name) source document3_V1.latestTrait).awaitResult
      eventually(elasticClient.execute(count(IndexV1.name)).awaitResult.result.count shouldBe 3)

      spawnEvolver(IndexV2) ! EvolveDocuments(Nil)

      val document1_V2 = IndexV2.DocumentV1(document1_V1.firstName, document1_V1.lastName, None).migrate
      val document3_V2 = IndexV2.DocumentV1(document3_V1.firstName, document3_V1.lastName, None).migrate

      eventually(elasticClient.execute(search(IndexV2.name)).awaitResult.result.hits.hits.map(_.sourceAsString).map(parse(_).right.get) should contain theSameElementsAs Seq(
        document1_V2.latestTrait.asJson,
        document2_V2,
        document3_V2.latestTrait.asJson
      ))
    }
    "run batch updates" in new EvolverContext(IndexV1) {
      evolver ! MigrateIndices(Nil, forceReindex = false)
      eventually(elasticClient.execute(catAliases()).awaitResult.result should not be empty)

      val document = IndexV1.DocumentV1("first1", "last1", Some(1))
      elasticClient.execute(indexInto(IndexV1.name) source document.latestTrait).awaitResult
      eventually(elasticClient.execute(count(IndexV1.name)).awaitResult.result.count shouldBe 1)

      evolver ! RunBatchUpdates(IndexV1.name, "clearAge")

      eventually(elasticClient.execute(search(IndexV2.name)).awaitResult.result.hits.hits.map(_.sourceAsString).map(parse(_).right.get) should contain theSameElementsAs Seq(
        document.latestTrait.asJson.mapObject(_.remove("age"))
      ))
    }
  }

  private def deleteAllIndicesAndAliases() =
    elasticClient.execute(catAliases()).flatMap(response => Future.sequence(
      response.result.map(aliasResponse => elasticClient.execute(removeAlias(aliasResponse.alias, aliasResponse.index)))
    )).flatMap(_ =>
      elasticClient.execute(catIndices()).flatMap(response => elasticClient.execute(deleteIndex(response.result.map(_.index): _*)))
    ).awaitResult

  //private def cleanAllIndices() = elasticClient.execute(catIndices()).flatMap(response => elasticClient.execute(clearIndex(response.result.map(_.index)))).awaitResult
  //

  class EvolverContext(indices: Index*) {
    def spawnEvolver(indices: Index*) = testKit.spawn(Evolver(elasticClient, if (indices.isEmpty) testIndices else indices))

    val evolver = spawnEvolver(indices: _ *)

    deleteAllIndicesAndAliases()
  }

}

object EvolverSpec {
  trait TestIndex1Base extends Index {
    override val name = "test_index1"

    override val latestVersion = latestVersionHelper[LatestCaseClass]

    override val mappings: Seq[FieldDefinition] = Seq(KeywordField("name"))

    sealed trait Versioned extends Schema.Versioned {
      override type LatestTrait = Document
    }

    sealed trait Document extends Versioned with Schema.Latest {
      override type LatestCaseClass = DocumentV3

      val name: String
      val deleted: Boolean
    }

    case class DocumentV3(name: String, deleted: Boolean) extends Document {
      override def migrate = this

      override def caseClass = this
    }

    case class DocumentV2(name: String) extends Versioned {
      override def migrate = DocumentV3(name, deleted = false).migrate
    }

    case class DocumentV1(firstName: String, lastName: String) extends Versioned {
      override def migrate = DocumentV2(s"$firstName $lastName").migrate
    }
    override implicit val versionedDecoder: Decoder[Versioned] = deriveConfiguredDecoder
    override implicit val latestInterfaceEncoder: Encoder[Document] = deriveConfiguredEncoder
  }

  object TestIndex1 extends TestIndex1Base

  object TestIndex1MappingUpdated extends TestIndex1Base {
    override val mappings: Seq[FieldDefinition] = Seq(TextField("name"))
  }

  object TestIndex1MappingAdded extends TestIndex1Base {
    override val mappings = Seq(KeywordField("name"), KeywordField("added"))
  }

  object TestIndex2 extends Index {
    override val name = "test_index2"

    override val latestVersion = latestVersionHelper[LatestCaseClass]

    case class Document(parameter: String) extends Schema.Versioned with Schema.Latest {

      override type LatestTrait = Document
      override type LatestCaseClass = Document

      override def migrate = this

      override def caseClass = this
    }
    override type Versioned = Document
    override implicit val versionedDecoder: Decoder[Versioned] = deriveConfiguredDecoder
    override implicit val latestInterfaceEncoder: Encoder[Document] = deriveConfiguredEncoder
  }

  object IndexV1 extends Index {
    override val name = "index"

    override val latestVersion = latestVersionHelper[LatestCaseClass]

    sealed trait Versioned extends Schema.Versioned {
      override type LatestTrait = Document
    }

    sealed trait Document extends Versioned with Schema.Latest {
      val firstName: String
      val lastName: String
      val age: Option[Int]

      override type LatestCaseClass = DocumentV1
    }

    case class DocumentV1(firstName: String, lastName: String, age: Option[Int] = None) extends Document {
      override def migrate: Document = this

      override def caseClass = this
    }

    override val batchUpdates = Seq(
      BatchUpdate("clearAge", _.caseClass.copy(age = None).latestTrait)
    )

    override implicit val versionedDecoder: Decoder[Versioned] = deriveConfiguredDecoder
    override implicit val latestInterfaceEncoder: Encoder[Document] = deriveConfiguredEncoder
  }

  object IndexV2 extends Index {
    override val name = IndexV1.name

    override val latestVersion = latestVersionHelper[LatestCaseClass]

    override val mappings = Seq(KeywordField("name"))

    sealed trait Versioned extends Schema.Versioned {
      override type LatestTrait = Document
    }

    sealed trait Document extends Versioned with Schema.Latest {
      val firstName: String
      val lastName: String
      val fullName: String
      override type LatestCaseClass = DocumentV2
    }

    case class DocumentV2(firstName: String, lastName: String, fullName: String) extends Document {
      override def migrate = this

      override def caseClass = this
    }

    case class DocumentV1(firstName: String, lastName: String, age: Option[Int] = None) extends Versioned {
      override def migrate = DocumentV2(firstName, lastName, s"$firstName $lastName").migrate
    }
    override implicit val versionedDecoder: Decoder[Versioned] = deriveConfiguredDecoder
    override implicit val latestInterfaceEncoder: Encoder[Document] = deriveConfiguredEncoder
  }
}

abstract class AkkaTypedSpec extends ScalaTestWithActorTestKit(
  // creating own ActorSystem to allow system.actorOf inside behavior (e.g. in elasticClient.publisher)
  ActorSystem("scalatest", {
    val debug = false

    ConfigFactory.parseString(
      if (debug)
        """
          |akka {
          |  log-dead-letters = 10
          |  log-dead-letters-during-shutdown = on
          |  loglevel = "DEBUG"
          |}
          |""".stripMargin else "")
      .withFallback(ConfigFactory.load())
  }).toTyped) with AnyWordSpecLike {
  implicit val classicActorSystem: ActorSystem = system.toClassic
  implicit val executionContext: ExecutionContext = system.executionContext

  def process() = Thread.sleep(3000)

  implicit class RichFuture[T](future: Future[T]) {
    def awaitResult(implicit duration: Duration = 3.seconds): T = Await.result(future, duration)
  }
}
*/
