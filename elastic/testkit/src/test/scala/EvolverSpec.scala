package net.sc8s.elastic

import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import com.sksamuel.elastic4s.ElasticClient
import com.sksamuel.elastic4s.ElasticDsl.{RichFuture => _, _}
import com.sksamuel.elastic4s.akka.{AkkaHttpClient, AkkaHttpClientSettings}
import com.sksamuel.elastic4s.circe._
import com.sksamuel.elastic4s.fields.{ElasticField, KeywordField, TextField}
import com.sksamuel.elastic4s.handlers.index.Field
import com.softwaremill.macwire.wireSet
import io.circe.generic.extras.semiauto._
import io.circe.parser._
import io.circe.syntax.EncoderOps
import io.circe.{Codec, Json}
import net.sc8s.akka.components.testkit.ClusterComponentTestKit
import net.sc8s.elastic.Evolver.Command.{EvolveDocuments, MigrateIndices, RunBatchUpdates}
import net.sc8s.elastic.Index.BatchUpdate
import net.sc8s.lagom.circe.testkit.ScalaTestWithActorTestKit
import net.sc8s.logstage.elastic.Logging
import org.scalatest.Inspectors._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.chaining.scalaUtilChainingOps

class EvolverSpec extends ScalaTestWithActorTestKit(Evolver.serializers) with AnyWordSpecLike with Matchers with Logging with ClusterComponentTestKit {

  implicit lazy val elasticClient: ElasticClient = ElasticClient(AkkaHttpClient(AkkaHttpClientSettings())(system.toClassic))

  implicit lazy val indexSetup: IndexSetup = IndexSetup(
    elasticClient,
    system,
    Some("evolver"),
    refreshImmediately = true
  )

  val testIndex1 = new EvolverSpec.TestIndex1()
  val testIndex2 = new EvolverSpec.TestIndex2()

  val indexV1 = new EvolverSpec.IndexV1()

  val elasticIndices: Set[Index] = wireSet

  def aliases() =
    elasticClient.execute(catAliases()).futureValue.result.filter(_.alias.pipe(elasticIndices.map(_.name).contains))

  "Evolver" should {
    "create indices, mappings and aliases" in new EvolverContext {
      eventually {
        aliases().map(_.alias) should contain theSameElementsAs elasticIndices.map(_.name)
        forAll(aliases().map(_.index)) { index =>
          val _index :: dateTime :: Nil = index.split("-", 2).toList
          elasticIndices.map(_.name) should contain(_index)

          LocalDateTime.parse(dateTime, Index.indexNameSuffixFormatter).until(LocalDateTime.now, ChronoUnit.SECONDS) should be <= 10L
        }
      }

      elasticClient.execute(getIndex(testIndex1.name)).futureValue.result.head._2.mappings.properties shouldBe Map(
        "name" -> Field(Some("keyword")),
        "class" -> Field(Some("keyword"))
      )
    }
    "keep existing aliases and indices" in new EvolverContext {
      val existingAliases = aliases()

      evolver ! MigrateIndices(Nil, forceReindex = false)

      Thread.sleep(3000)

      aliases() shouldBe existingAliases
    }

    def addDocumentV2() = {
      elasticClient.execute(indexInto(testIndex1.name) source testIndex1.DocumentV2("id1", "name1").asJson {
        import Index.configuration
        deriveConfiguredEncoder[testIndex1.DocumentV2]
      }).futureValue
      eventually(elasticClient.execute(count(testIndex1.name)).futureValue.result.count shouldBe 1)
    }

    "migrate to updated mapping by reindexing" in new EvolverContext(testIndex1) {
      val originalIndex = eventually {
        val aliases = elasticClient.execute(catAliases()).futureValue.result.find(_.alias == testIndex1.name)
        aliases should not be empty
        aliases.get.index
      }
      addDocumentV2()

      val testIndex1MappingUpdated = new EvolverSpec.TestIndex1MappingUpdated()

      spawnEvolver(testIndex1MappingUpdated) ! MigrateIndices(Seq(testIndex1MappingUpdated.name), forceReindex = false)

      eventually {
        elasticClient.execute(catAliases()).futureValue.result.filter(_.alias == testIndex1.name).map(_.index) should not contain originalIndex
        val mappings = elasticClient.execute(getIndex(testIndex1.name)).futureValue.result.head._2.mappings
        mappings.properties shouldBe Map(
          "id" -> Field(Some("text")),
          "name" -> Field(Some("text")),
          "class" -> Field(Some("keyword")),
        )
        mappings.meta.get(Evolver.mappingsHashField) should contain(testIndex1MappingUpdated.mappingsHash)
        elasticClient.execute(count(testIndex1.name)).futureValue.result.count shouldBe 1
      }
    }
    // not implemented yet
    //"migrate by adding mapping" in new EvolverContext(testIndex1) {
    //  val originalIndex = eventually {
    //    val aliases = elasticClient.execute(catAliases()).futureValue.result.find(_.alias == testIndex1.name)
    //    aliases should not be empty
    //    aliases.get.index
    //  }
    //
    //  addDocumentV2()
    //
    //  val testIndex1MappingAdded = new EvolverSpec.TestIndex1MappingAdded()
    //
    //  spawnEvolver(testIndex1MappingAdded)
    //
    //  eventually {
    //    elasticClient.execute(catAliases()).futureValue.result.filter(_.alias == testIndex1.name).map(_.index) should contain(originalIndex)
    //    val mappings = elasticClient.execute(getIndex(testIndex1.name)).futureValue.result.head._2.mappings
    //    mappings.properties shouldBe Map("name" -> Field(Some("keyword")), "added" -> Field(Some("keyword")), "class" -> Field(Some("keyword")))
    //    mappings.meta.get(Evolver.mappingsHashField) should contain(testIndex1MappingAdded.mappingsHash)
    //    elasticClient.execute(count(testIndex1.name)).futureValue.result.count shouldBe 1
    //  }
    //}
    "skip migration of mappings" in new EvolverContext(testIndex1) {
      elasticClient.execute(putMapping(testIndex1.name) properties Seq(KeywordField("deleted"))).futureValue
      evolver ! MigrateIndices(Nil, forceReindex = false)

      Thread.sleep(3000)

      elasticClient.execute(getIndex(testIndex1.name)).futureValue.result.head._2.mappings.properties === Map("name" -> Field(Some("keyword")), "deleted" -> Field(Some("keyword")))
    }
    "evolve documents with older version" in new EvolverContext(indexV1) {
      val indexV2 = new EvolverSpec.IndexV2()

      val document1_V1 = indexV1.DocumentV1("id1", "first1", "last1", Some(1))
      val document2_V2 = Json.obj(
        "class" -> indexV2.latestVersion.asJson,
        "skip this" -> "otherwise it would fail evolution".asJson
      )
      val document3_V1 = indexV1.DocumentV1("id3", "first3", "last3", Some(3))

      import indexV1.codec

      elasticClient.execute(indexInto(indexV1.name) source document1_V1.asLatest).futureValue
      elasticClient.execute(indexInto(indexV1.name) source document2_V2).futureValue
      elasticClient.execute(indexInto(indexV1.name) source document3_V1.asLatest).futureValue
      eventually(elasticClient.execute(count(indexV1.name)).futureValue.result.count shouldBe 3)

      val evolverV2 = spawnEvolver(indexV2)
      // wait for initial migration
      Thread.sleep(3000)

      evolverV2 ! EvolveDocuments(Nil)

      val document1_V2 = indexV2.DocumentV1("id1", document1_V1.firstName, document1_V1.lastName, None).evolve
      val document3_V2 = indexV2.DocumentV1("id3", document3_V1.firstName, document3_V1.lastName, None).evolve

      eventually(elasticClient.execute(search(indexV2.name)).futureValue.result.hits.hits.map(_.sourceAsString).map(parse(_).toOption.get) should contain theSameElementsAs Seq(
        document1_V2.asLatest.asJson(indexV2.codec),
        document2_V2,
        document3_V2.asLatest.asJson(indexV2.codec),
      ))
    }
    "run batch updates" in new EvolverContext(indexV1) {
      import indexV1.codec

      val document = indexV1.DocumentV1("id1", "first1", "last1", Some(1))
      elasticClient.execute(indexInto(indexV1.name) source document.asLatest).futureValue
      eventually(elasticClient.execute(count(indexV1.name)).futureValue.result.count shouldBe 1)

      evolver ! RunBatchUpdates(indexV1.name, "clearAge")

      eventually(elasticClient.execute(search(indexV1.name)).futureValue.result.hits.hits.map(_.sourceAsString).map(parse(_).toOption.get) should contain theSameElementsAs Seq(
        document.asLatest.asJson.mapObject(_.remove("age"))
      ))
    }
  }

  private def deleteAllIndicesAndAliases() = {
    elasticClient
      .execute(catAliases()).flatMap(response => Future.sequence(
        response.result.filter(_.alias.pipe(elasticIndices.map(_.name).contains)).map(aliasResponse => elasticClient.execute(removeAlias(aliasResponse.alias, aliasResponse.index)))
      ))
      .flatMap(_ =>
        elasticClient.execute(catIndices()).flatMap(response => if (response.result.nonEmpty) elasticClient.execute(deleteIndex(response.result.filter(_.index.pipe(index => elasticIndices.map(_.name).exists(index.startsWith))).map(_.index): _*)) else Future.successful())
      )
      .futureValue
  }

  class EvolverContext(indices: Index*) {
    private def allOrCustomIndices(indices: Seq[Index]) = if (indices.isEmpty) elasticIndices else indices.toSet
    deleteAllIndicesAndAliases()

    def spawnEvolver(indices: Index*) = spawnComponent(Evolver)(new Evolver.Component(elasticClient, allOrCustomIndices(indices)))

    val evolver = spawnEvolver(indices: _ *)

    // wait for automatic migration to finish
    eventually(aliases().map(_.alias) should contain theSameElementsAs allOrCustomIndices(indices).map(_.name))
  }
}

object EvolverSpec {
  abstract class TestIndex1Base extends Index.StringId("test_index1") {
    override val mappings = Seq[ElasticField](KeywordField(fieldName(_.name)))

    override type LatestCaseClass = DocumentV3

    sealed trait Version extends VersionT

    sealed trait Latest extends LatestT with Version {
      val id: String
      val name: String
      val deleted: Boolean
    }

    case class DocumentV3(id: String, name: String, deleted: Boolean) extends Latest {
      override def caseClass = this

      override def evolve = this
    }

    case class DocumentV2(id: String, name: String) extends Version {
      override def evolve = DocumentV3(id, name, deleted = false).evolve
    }

    case class DocumentV1(id: String, firstName: String, lastName: String) extends Version {
      override def evolve = DocumentV2(id, s"$firstName $lastName").evolve
    }

    override val latestVersion = latestVersionHelper[LatestCaseClass]

    override implicit val codec: Codec[Latest] = evolvingCodec {
      import io.circe.generic.extras.auto._
      deriveConfiguredCodec[Version]
    }
  }

  class TestIndex1(implicit val indexSetup: IndexSetup) extends TestIndex1Base

  class TestIndex1MappingUpdated(implicit val indexSetup: IndexSetup) extends TestIndex1Base {
    override val mappings = Seq(TextField(fieldName(_.name)))
  }

  class TestIndex1MappingAdded(implicit val indexSetup: IndexSetup) extends TestIndex1Base {
    override val mappings = Seq(TextField(fieldName(_.name)), KeywordField("added"))
  }

  class TestIndex2(implicit val indexSetup: IndexSetup) extends Index.StringId("test_index2") {

    override type LatestCaseClass = Document

    sealed trait Version extends VersionT

    sealed trait Latest extends LatestT with Version {
      val id: String
      val name: String
    }

    case class Document(id: String, name: String) extends Latest {
      override def caseClass = this

      override def evolve = this
    }

    override val latestVersion = latestVersionHelper[LatestCaseClass]

    override implicit val codec: Codec[Latest] = evolvingCodec {
      import io.circe.generic.extras.auto._
      deriveConfiguredCodec[Version]
    }
  }

  class IndexV1(implicit val indexSetup: IndexSetup) extends Index.StringId("index") {

    override type LatestCaseClass = DocumentV1

    sealed trait Version extends VersionT

    sealed trait Latest extends LatestT with Version {
      val id: String
      val firstName: String
      val lastName: String
      val age: Option[Int]
    }

    case class DocumentV1(id: String, firstName: String, lastName: String, age: Option[Int] = None) extends Latest {
      override def caseClass = this

      override def evolve = this
    }

    override val batchUpdates = Seq(
      BatchUpdate("clearAge", _.caseClass.copy(age = None))
    )

    override val latestVersion = latestVersionHelper[LatestCaseClass]

    override implicit val codec: Codec[Latest] = evolvingCodec {
      import io.circe.generic.extras.auto._
      deriveConfiguredCodec[Version]
    }
  }

  class IndexV2(implicit val indexSetup: IndexSetup) extends Index.StringId("index") {
    override type LatestCaseClass = DocumentV2

    override val mappings = Seq(KeywordField(fieldName(_.firstName)))

    sealed trait Version extends VersionT

    sealed trait Latest extends LatestT with Version {
      val id: String
      val firstName: String
      val lastName: String
      val fullName: String
    }

    case class DocumentV2(id: String, firstName: String, lastName: String, fullName: String) extends Latest {
      override def caseClass = this

      override def evolve = this
    }

    case class DocumentV1(id: String, firstName: String, lastName: String, age: Option[Int] = None) extends Version {
      override def evolve = DocumentV2(id, firstName, lastName, s"$firstName $lastName").evolve
    }

    override val latestVersion = latestVersionHelper[LatestCaseClass]

    override implicit val codec: Codec[Latest] = evolvingCodec {
      import io.circe.generic.extras.auto._
      deriveConfiguredCodec[Version]
    }
  }
}

//abstract class AkkaTypedSpec extends ScalaTestWithActorTestKit(
//  // creating own ActorSystem to allow system.actorOf inside behavior (e.g. in elasticClient.publisher)
//  ActorSystem("scalatest", {
//    val debug = false
//
//    ConfigFactory.parseString(
//      if (debug)
//        """
//          |akka {
//          |  log-dead-letters = 10
//          |  log-dead-letters-during-shutdown = on
//          |  loglevel = "DEBUG"
//          |}
//          |""".stripMargin else "")
//      .withFallback(ConfigFactory.load())
//  }).toTyped) with AnyWordSpecLike {
//  implicit val classicActorSystem: ActorSystem = system.toClassic
//  implicit val executionContext: ExecutionContext = system.executionContext
//
//  def process() = Thread.sleep(3000)
//
//  implicit class RichFuture[T](future: Future[T]) {
//    def awaitResult(implicit duration: Duration = 3.seconds): T = Await.result(future, duration)
//  }
//}
