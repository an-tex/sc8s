import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._
import sbt._

object Dependencies {
  val scala213 = "2.13.4"

  val scalaTest = Def.setting("org.scalatest" %%% "scalatest" % "3.2.3")
  val scalamock = "org.scalamock" %% "scalamock" % "5.1.0" % Test

  object lagom {
    private val version = "1.6.4"
    val scaladslServer = "com.lightbend.lagom" %% "lagom-scaladsl-server" % version
  }

  object akka {
    private val version = "2.6.11"
    val jsVersion = "2.2.6.9"
    private val httpVersion = "10.1.13"

    val actor = "com.typesafe.akka" %% "akka-actor" % version
    val stream = "com.typesafe.akka" %% "akka-stream" % version
    val http = "com.typesafe.akka" %% "akka-http" % httpVersion
    val typed = "com.typesafe.akka" %% "akka-actor-typed" % version
    val persistenceTyped = "com.typesafe.akka" %% "akka-persistence-typed" % version
    val persistenceTestkit = "com.typesafe.akka" %% "akka-persistence-testkit" % version
    val persistenceCassandra = "com.typesafe.akka" %% "akka-persistence-cassandra" % "1.0.4"
    val streamTyped = "com.typesafe.akka" %% "akka-stream-typed" % version
    val testkit = "com.typesafe.akka" %% "akka-testkit" % version
    val testkitTyped = "com.typesafe.akka" %% "akka-actor-testkit-typed" % version
    val streamTestkit = "com.typesafe.akka" %% "akka-stream-testkit" % version % Test

    val httpCirce = "de.heikoseeberger" %% "akka-http-circe" % "1.35.3"

    val overrides = Seq(
      "akka-http",
      "akka-http-core",
      "akka-http-spray-json",
      "akka-parsing",
      "akka-http-xml"
    ).map("com.typesafe.akka" %% _ % httpVersion) ++
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
      ).map("com.typesafe.akka" %% _ % version)
  }

  object macwire {
    val version = "2.3.7"

    val macros = "com.softwaremill.macwire" %% "macros" % version % "provided"
    val macrosAkka = "com.softwaremill.macwire" %% "macrosakka" % version % "provided"
    val util = "com.softwaremill.macwire" %% "util" % version
  }

  object circe {
    private val version = "0.13.0"

    val core = Def.setting("io.circe" %%% "circe-core" % version)
    val generic = Def.setting("io.circe" %%% "circe-generic" % version)
    val genericExtras = Def.setting("io.circe" %%% "circe-generic-extras" % version)
    val parser = Def.setting("io.circe" %%% "circe-parser" % version)
  }

  object logstage {
    private val version = "1.0.3"

    val core = Def.setting("io.7mind.izumi" %%% "logstage-core" % version)
    val circe = Def.setting("io.7mind.izumi" %%% "logstage-rendering-circe" % version)
    val fromSlf4j = "io.7mind.izumi" %% "logstage-adapter-slf4j" % version
    val toSlf4j = "io.7mind.izumi" %% "logstage-sink-slf4j" % version
  }

  object cats {
    private val version = "2.2.0"
    val core = Def.setting("org.typelevel" %%% "cats-core" % version)
  }
}
