import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._
import sbt._

object Dependencies {
  val scala213 = "2.13.6"

  val scalaTest = Def.setting("org.scalatest" %%% "scalatest" % "3.2.9")
  val scalamock = "org.scalamock" %% "scalamock" % "5.1.0" % Test
  val slf4j = "org.slf4j" % "slf4j-api" % "1.7.30"

  object play {
    // same as lagom uses
    val core = "com.typesafe.play" %% "play" % "2.8.4"
  }

  object lagom {
    private val lagomVersion = "1.6.5"
    val scaladslServer = "com.lightbend.lagom" %% "lagom-scaladsl-server" % lagomVersion
    val scaladslApi = "com.lightbend.lagom" %% "lagom-scaladsl-api" % lagomVersion

    object js {
      private val lagomJsVersion = "0.4.0-1.6.4"

      val scalaDslApi = Def.setting("com.github.mliarakos.lagomjs" %%% "lagomjs-scaladsl-api" % lagomJsVersion)
      val scalaDslClient = Def.setting("com.github.mliarakos.lagomjs" %%% "lagomjs-scaladsl-client" % lagomJsVersion)
    }
  }

  object akka {
    private val akkaVersion = "2.6.11"
    private val akkaHttpVersion = "10.1.13"

    val actor = "com.typesafe.akka" %% "akka-actor" % akkaVersion
    val stream = "com.typesafe.akka" %% "akka-stream" % akkaVersion
    val http = "com.typesafe.akka" %% "akka-http" % akkaHttpVersion
    val typed = "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion
    val persistenceTyped = "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion
    val persistenceTestkit = "com.typesafe.akka" %% "akka-persistence-testkit" % akkaVersion
    val persistenceCassandra = "com.typesafe.akka" %% "akka-persistence-cassandra" % "1.0.4"
    val streamTyped = "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion
    val testkit = "com.typesafe.akka" %% "akka-testkit" % akkaVersion
    val testkitTyped = "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion
    val streamTestkit = "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test

    val httpCirce = "de.heikoseeberger" %% "akka-http-circe" % "1.35.3"

    val overrides = Seq(
      "akka-http",
      "akka-http-core",
      "akka-http-spray-json",
      "akka-parsing",
      "akka-http-xml"
    ).map("com.typesafe.akka" %% _ % akkaHttpVersion) ++
      Seq(
        "akka-actor",
        "akka-actor-testkit-typed",
        "akka-actor-typed",
        "akka-cluster",
        "akka-cluster-sharding",
        "akka-cluster-sharding-typed",
        "akka-cluster-tools",
        "akka-cluster-typed",
        "akka-coordination",
        "akka-discovery",
        "akka-distributed-data",
        "akka-persistence",
        "akka-persistence-query",
        "akka-persistence-typed",
        "akka-protobuf-v3",
        "akka-remote",
        "akka-serialization-jackson",
        "akka-slf4j",
        "akka-stream"
      ).map("com.typesafe.akka" %% _ % akkaVersion)
  }

  object macwire {
    val macwireVersion = "2.3.7"

    val macros = "com.softwaremill.macwire" %% "macros" % macwireVersion % "provided"
    val macrosAkka = "com.softwaremill.macwire" %% "macrosakka" % macwireVersion % "provided"
    val util = "com.softwaremill.macwire" %% "util" % macwireVersion
  }

  object circe {
    private val circeVersion = "0.13.0"

    val core = Def.setting("io.circe" %%% "circe-core" % circeVersion)
    val generic = Def.setting("io.circe" %%% "circe-generic" % circeVersion)
    val genericExtras = Def.setting("io.circe" %%% "circe-generic-extras" % circeVersion)
    val parser = Def.setting("io.circe" %%% "circe-parser" % circeVersion)
  }

  object logstage {
    private val izumiVersion = "1.0.3"

    val core = Def.setting("io.7mind.izumi" %%% "logstage-core" % izumiVersion)
    val circe = Def.setting("io.7mind.izumi" %%% "logstage-rendering-circe" % izumiVersion)
    val fromSlf4j = "io.7mind.izumi" %% "logstage-adapter-slf4j" % izumiVersion
    val toSlf4j = "io.7mind.izumi" %% "logstage-sink-slf4j" % izumiVersion
  }

  object cats {
    val core = Def.setting("org.typelevel" %%% "cats-core" % "2.2.0")
  }
}
