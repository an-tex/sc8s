package net.sc8s.elastic

import akka.actor.Status.Success
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import com.sksamuel.elastic4s.{Hit, Indexable}
import com.softwaremill.macwire.wireSet
import io.circe.Codec
import io.circe.generic.extras.semiauto._
import io.circe.syntax._
import net.sc8s.elastic.testkit.ElasticIndexTesting
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class IndexSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers with ElasticIndexTesting {
  private val index = new IndexSpec.Index

  import index._

  "Index" should {
    val documentV1 = DocumentV1("id", "first", "last")
    val documentV2 = DocumentV2("id", "first last")

    "migrate" in {
      documentV1.evolve shouldBe documentV2
    }
    "index as latest" in {
      def source[T](t: T)(implicit indexable: Indexable[T]) = indexable.json(t)

      source(documentV2.asLatest) === documentV2.asLatest.asJson.noSpaces
    }
    "hit read versioned with migration to latest" in {
      versionedHitReader.read(new Hit {
        override def id = "id"

        override def index = ???

        override def version = ???

        override def sourceAsString = documentV1.asInstanceOf[Version].asJson {
          import Index.configuration
          import io.circe.generic.extras.auto._
          deriveConfiguredEncoder[Version]
        }.noSpaces

        override def sourceAsMap = ???

        override def exists = ???

        override def score = ???

        override def seqNo = ???

        override def primaryTerm = ???
      }).toString === Success(documentV1.evolve).toString
    }
    "latest version" in {
      index.asInstanceOf[Index].latestVersion === "DocumentV2"
    }
  }

  override val elasticIndices = wireSet
}

object IndexSpec {

  class Index(implicit val indexSetup: IndexSetup) extends Index.StringId("index") {
    override type LatestCaseClass = DocumentV2

    sealed trait Version extends VersionT

    sealed trait Latest extends LatestT with Version {
      val id: String
      val name: String
    }

    case class DocumentV2(id: String, name: String) extends Latest {
      override def caseClass = this

      override def evolve = this
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
}
