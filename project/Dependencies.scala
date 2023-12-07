import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._
import sbt._

object Dependencies {
  val scala213 = "2.13.12"

  val scalaTest = Def.setting("org.scalatest" %%% "scalatest" % "3.2.16")
  val specs2 = Def.setting("org.scalatest" %%% "scalatest" % "3.2.16")
  val scalamock = "org.scalamock" %% "scalamock" % "5.2.0" % Test
  val slf4j = "org.slf4j" % "slf4j-api" % "2.0.9"
  val scalaJavaTime = Def.setting("io.github.cquiroz" %%% "scala-java-time" % "2.5.0")
  val scalaXml = "org.scala-lang.modules" %% "scala-xml" % "2.1.0"
  val nameOf = "com.github.dwickern" %% "scala-nameof" % "3.0.0-antex"

  val overrides = akka.overrides ++ Seq(
    /*
    need to wait for new releases of those
    [error] 	* org.scala-lang.modules:scala-xml_2.13:2.1.0 (early-semver) is selected over {1.2.0}
    [error] 	    +- org.scalatest:scalatest-core_2.13:3.2.13           (depends on 2.1.0)
    [error] 	    +- com.typesafe.play:twirl-api_2.13:1.5.1             (depends on 1.2.0)
    [error] 	    +- com.typesafe.play:play-ws-standalone-xml_2.13:2.1.6 (depends on 1.2.0)
    [error] 	    +- com.lightbend.lagom:lagom-api_2.13:1.6.7           (depends on 1.2.0)
    [error] 	    +- com.lightbend.lagom:lagom-akka-management-core_2.13:1.6.7 (depends on 1.2.0)
    but safe to upgrade as there were no code changes necessary:
    - https://github.com/playframework/twirl/pull/525/files
    - https://github.com/lagom/lagom/pull/3333/files
     */
    scalaXml,
  )

  object play {
    // same as lagom uses
    val core = "com.typesafe.play" %% "play" % "2.8.21"
  }

  object lagom {
    private val lagomVersion = "1.6.7"
    val scaladslServer = "com.lightbend.lagom" %% "lagom-scaladsl-server" % lagomVersion
    val scaladslApi = "com.lightbend.lagom" %% "lagom-scaladsl-api" % lagomVersion

    object js {
      private val lagomJsVersion = "0.5.1-1.6.5"

      val scalaDslApi = Def.setting("com.github.mliarakos.lagomjs" %%% "lagomjs-scaladsl-api" % lagomJsVersion)
      val scalaDslClient = Def.setting("com.github.mliarakos.lagomjs" %%% "lagomjs-scaladsl-client" % lagomJsVersion)
    }
  }

  object akka {
    private val akkaVersion = "2.6.20"
    private val akkaHttpVersion = "10.2.10"
    private val akkaJs = "2.2.6.14"
    private val r2dbcVersion = "0.7.7"

    val actor = "com.typesafe.akka" %% "akka-actor" % akkaVersion
    val clusterShardingTyped = "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion
    val http = "com.typesafe.akka" %% "akka-http" % akkaHttpVersion
    val persistenceCassandra = "com.typesafe.akka" %% "akka-persistence-cassandra" % "1.0.6"
    val persistenceR2dbc = "com.lightbend.akka" %% "akka-persistence-r2dbc" % r2dbcVersion
    val persistenceTestkit = "com.typesafe.akka" %% "akka-persistence-testkit" % akkaVersion
    val persistenceTyped = "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion
    val stream = "com.typesafe.akka" %% "akka-stream" % akkaVersion
    val streamTestkit = "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test
    val streamTyped = "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion
    val testkit = "com.typesafe.akka" %% "akka-testkit" % akkaVersion
    val testkitTyped = "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion
    val typed = "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion

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
        "akka-stream",
        "akka-stream-typed",
        "akka-stream-testkit"
      ).map("com.typesafe.akka" %% _ % akkaVersion)

    object projection {
      private val projectionVersion = "1.2.5"

      val eventsourced = "com.lightbend.akka" %% "akka-projection-eventsourced" % projectionVersion
      val cassandra = "com.lightbend.akka" %% "akka-projection-cassandra" % projectionVersion
      val r2dbc = "com.lightbend.akka" %% "akka-projection-r2dbc" % r2dbcVersion
      val testKit = "com.lightbend.akka" %% "akka-projection-testkit" % projectionVersion
    }

    object js {
      val stream = Def.setting("org.akka-js" %%% "akkajsactorstream" % akkaJs)
    }
  }

  object macwire {
    val macwireVersion = "2.5.9"

    val macros = "com.softwaremill.macwire" %% "macros" % macwireVersion % "provided"
    val macrosAkka = "com.softwaremill.macwire" %% "macrosakka" % macwireVersion % "provided"
    val util = "com.softwaremill.macwire" %% "util" % macwireVersion
  }

  object circe {
    private val circeVersion = "0.14.6"

    val core = Def.setting("io.circe" %%% "circe-core" % circeVersion)
    val generic = Def.setting("io.circe" %%% "circe-generic" % circeVersion)
    val genericExtras = Def.setting("io.circe" %%% "circe-generic-extras" % "0.14.3")
    val parser = Def.setting("io.circe" %%% "circe-parser" % circeVersion)
  }

  object logstage {
    private val izumiVersion = "1.0.10"

    val core = Def.setting("io.7mind.izumi" %%% "logstage-core" % izumiVersion)
    val circe = Def.setting("io.7mind.izumi" %%% "logstage-rendering-circe" % izumiVersion)
    val fromSlf4j = "io.7mind.izumi" %% "logstage-adapter-slf4j" % izumiVersion
    val toSlf4j = "io.7mind.izumi" %% "logstage-sink-slf4j" % izumiVersion
  }

  object cats {
    val core = Def.setting("org.typelevel" %%% "cats-core" % "2.10.0")
  }

  object logback {
    private val logback = "1.4.14"

    val core = "ch.qos.logback" % "logback-core" % logback
    val classic = "ch.qos.logback" % "logback-classic" % logback
  }

  object elastic4s {
    private val elastic4s = "8.11.3"
    private val elasticsearch = "8.11.2"

    val clientAkka = "com.sksamuel.elastic4s" %% "elastic4s-client-akka" % elastic4s
    val clientJava = "com.sksamuel.elastic4s" %% "elastic4s-client-esjava" % elastic4s
    val core = "com.sksamuel.elastic4s" %% "elastic4s-core" % elastic4s
    val elasticTestFramework = "org.elasticsearch.test" % "framework" % elasticsearch
    val httpStreams = "com.sksamuel.elastic4s" %% "elastic4s-http-streams" % elastic4s
    val jsonCirce = "com.sksamuel.elastic4s" %% "elastic4s-json-circe" % elastic4s
    val jsonPlay = "com.sksamuel.elastic4s" %% "elastic4s-json-play" % elastic4s
    // does not work, use httpStreams instead
    val streamsAkka = "com.sksamuel.elastic4s" %% "elastic4s-streams-akka" % elastic4s
    val testkit = "com.sksamuel.elastic4s" %% "elastic4s-testkit" % elastic4s
  }
}
