import Dependencies.*
import sbtcrossproject.CrossType
import sbtghactions.JavaSpec.Distribution.Adopt

lazy val sc8s = (project in file("."))
  .settings(
    publish / skip := true
  )
  .aggregate(
    `akka-circe`,
    `akka-components`,
    `akka-components-lagom`,
    `akka-components-testkit`,
    `akka-components-persistence-cassandra-lagom-api`.js,
    `akka-components-persistence-cassandra-lagom-api`.jvm,
    `akka-components-persistence-cassandra-lagom`,
    `akka-components-persistence-projection`,
    `akka-components-persistence-projection-cassandra`,
    `akka-components-persistence-projection-r2dbc`,
    `akka-components-persistence-projection-api`.js,
    `akka-components-persistence-projection-api`.jvm,
    `akka-components-persistence-cassandra-lagom-api`.js,
    `akka-components-persistence-cassandra-lagom-api`.jvm,
    `akka-components-persistence-cassandra-lagom`,
    `akka-components-persistence-r2dbc-lagom-api`.js,
    `akka-components-persistence-r2dbc-lagom-api`.jvm,
    `akka-components-persistence-r2dbc-common`,
    `akka-components-persistence-r2dbc-lagom`,
    `akka-components-persistence-r2dbc-tapir`,
    `akka-components-persistence-projection-lagom-api`.js,
    `akka-components-persistence-projection-lagom-api`.jvm,
    `akka-components-persistence-projection-common`,
    `akka-components-persistence-projection-lagom`,
    `akka-components-persistence-projection-tapir`,
    `akka-components-persistence-utils`,
    `akka-stream-utils`.js,
    `akka-stream-utils`.jvm,
    `common-circe`.js,
    `common-circe`.jvm,
    `common-tzdb`.js,
    `elastic-core`,
    `elastic-lagom-api`.js,
    `elastic-lagom-api`.jvm,
    `elastic-lagom-service`,
    `elastic-testkit`,
    `lagom-api-circe`.js,
    `lagom-api-circe`.jvm,
    `lagom-server-circe-testkit`,
    `lagom-server-circe`,
    `logstage-elastic`,
    `schevo`.js,
    `schevo`.jvm,
    `schevo-circe`.js,
    `schevo-circe`.jvm,
    `schevo-circe-example-akka`
  )

lazy val `schevo` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("schevo"))
  .settings(
    libraryDependencies ++= Seq(
      scalaTest.value % Test,
    ),
    idePackagePrefix := Some("net.sc8s.schevo")
  )

lazy val `schevo-circe` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("schevo-circe"))
  .settings(
    libraryDependencies ++= Seq(
      circe.core.value,
      circe.parser.value,
      circe.generic.value,
      circe.genericExtras.value,
      scalaTest.value % Test,
    ),
    idePackagePrefix := Some("net.sc8s.schevo.circe")
  ).dependsOn(`common-circe`, `schevo`)

lazy val `schevo-circe-example-akka` = project
  .settings(
    libraryDependencies ++= Seq(
      akka.typed,
      akka.stream,
      akka.persistenceTyped,
      circe.core.value,
      circe.parser.value,
      circe.generic.value,
      circe.genericExtras.value,
      scalaTest.value % Test,
      akka.testkitTyped % Test,
      akka.persistenceTestkit % Test
    ),
    idePackagePrefix := Some("net.sc8s.schevo.circe.example.akka")
  ).dependsOn(`common-circe`.jvm, `akka-circe`, `schevo-circe`.jvm)

lazy val `akka-circe` = project
  .settings(
    libraryDependencies ++= Seq(
      akka.typed,
      akka.stream,
      circe.core.value,
      circe.parser.value,
      circe.generic.value,
      circe.genericExtras.value,
      scalaTest.value % Test,
      akka.testkitTyped % Test,
    ),
  ).dependsOn(`common-circe`.jvm)

lazy val `akka-components` = project
  .settings(
    libraryDependencies ++= Seq(
      akka.persistenceTyped,
      akka.persistenceTestkit,
      scalaTest.value,
      scalamock,
      macwire.macros
    ),
    idePackagePrefix := Some("net.sc8s.akka.components")
  )
  .dependsOn(`akka-circe`, `akka-components-persistence-projection`, `lagom-server-circe`, `akka-components-persistence-utils`)

lazy val `akka-components-lagom` = project
  .settings(
    libraryDependencies ++= Seq(
      lagom.scaladslServer,
      macwire.macros
    ),
    idePackagePrefix := Some("net.sc8s.akka.components.lagom")
  )
  .dependsOn(`akka-components`)

lazy val `akka-components-testkit` = project
  .settings(
    libraryDependencies ++= Seq(
      scalaTest.value,
      scalamock,
      akka.testkitTyped % Test,
      akka.persistenceTestkit % Test,
      akka.projection.testKit,
      macwire.macros,
      logback.classic % Test,
      logback.core % Test,
    ),
    dependencyOverrides ++= Dependencies.akka.createOverrides(licensed = true),
    idePackagePrefix := Some("net.sc8s.akka.components.testkit")
  )
  .dependsOn(`akka-components`, `lagom-server-circe-testkit`, `akka-components-persistence-projection-cassandra` % Test, `akka-components-persistence-projection-r2dbc` % Test)

lazy val `akka-components-persistence-cassandra-lagom-api` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("akka-components-persistence-cassandra-lagom-api"))
  .jvmSettings(libraryDependencies += lagom.scaladslApi)
  .jsSettings(libraryDependencies += lagom.js.scalaDslApi.value)
  .settings(
    libraryDependencies ++= Seq(
      circe.core.value,
      circe.parser.value,
    ),
    idePackagePrefix := Some("net.sc8s.akka.components.persistence.cassandra.lagom.api")
  )
  .dependsOn(`common-circe`, `lagom-api-circe`)

lazy val `akka-components-persistence-cassandra-lagom` = project
  .settings(
    libraryDependencies ++= Seq(
      lagom.scaladslServer,
      macwire.macros,
      akka.persistenceCassandra,
    ),
    idePackagePrefix := Some("net.sc8s.akka.components.persistence.cassandra.lagom")
  )
  .dependsOn(`akka-components`, `akka-components-persistence-cassandra-lagom-api`.jvm)

lazy val `akka-components-persistence-r2dbc-lagom-api` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("akka-components-persistence-r2dbc-lagom-api"))
  .jvmSettings(libraryDependencies += lagom.scaladslApi)
  .jsSettings(libraryDependencies += lagom.js.scalaDslApi.value)
  .settings(
    idePackagePrefix := Some("net.sc8s.akka.components.persistence.r2dbc.lagom.api")
  )
  .dependsOn(`common-circe`, `lagom-api-circe`)

lazy val `akka-components-persistence-r2dbc-common` = project
  .settings(
    libraryDependencies ++= Seq(
      akka.persistenceR2dbc,
    ),
    dependencyOverrides ++= Dependencies.akka.createOverrides(licensed = true),
    idePackagePrefix := Some("net.sc8s.akka.components.persistence.r2dbc.common")
  )
  .dependsOn(`akka-components`)

lazy val `akka-components-persistence-r2dbc-lagom` = project
  .settings(
    libraryDependencies ++= Seq(
      lagom.scaladslServer,
    ),
    dependencyOverrides ++= Dependencies.akka.createOverrides(licensed = true),
    idePackagePrefix := Some("net.sc8s.akka.components.persistence.r2dbc.lagom")
  )
  .dependsOn(`akka-components`, `akka-components-persistence-r2dbc-lagom-api`.jvm, `akka-components-persistence-r2dbc-common`)

lazy val `akka-components-persistence-r2dbc-tapir` = project
  .settings(
    libraryDependencies ++= Seq(
      tapir.core.value,
      tapir.circe.value,
    ),
    dependencyOverrides ++= Dependencies.akka.createOverrides(licensed = true),
    idePackagePrefix := Some("net.sc8s.akka.components.persistence.r2dbc.tapir")
  )
  .dependsOn(`akka-components`, `akka-components-persistence-r2dbc-common`)

lazy val `akka-components-persistence-projection` = project
  .settings(
    libraryDependencies ++= Seq(
      akka.persistenceTyped,
      akka.projection.eventsourced,
      akka.clusterShardingTyped,
    ),
    idePackagePrefix := Some("net.sc8s.akka.components.persistence.projections"),
  )
  .dependsOn(`akka-components-persistence-projection-api`.jvm, `logstage-elastic`, `akka-circe`)

lazy val `akka-components-persistence-projection-api` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("akka-components-persistence-projection-api"))
  .settings(
    libraryDependencies ++= Seq(
      circe.core.value,
      circe.genericExtras.value,
      circe.parser.value,
    ),
    idePackagePrefix := Some("net.sc8s.akka.components.persistence.projection.api"),
  )
  .dependsOn(`common-circe`)

lazy val `akka-components-persistence-projection-cassandra` = project
  .settings(
    libraryDependencies ++= Seq(
      akka.clusterShardingTyped,
      akka.persistenceCassandra,
      akka.projection.cassandra,
      akka.projection.eventsourced,
      circe.core.value,
      circe.generic.value,
      circe.genericExtras.value,
    ),
  ).dependsOn(`akka-components`, `akka-circe`, `akka-components-persistence-projection`, `logstage-elastic`)

lazy val `akka-components-persistence-projection-r2dbc` = project
  .settings(
    libraryDependencies ++= Seq(
      akka.clusterShardingTyped,
      akka.persistenceR2dbc,
      akka.projection.eventsourced,
      akka.projection.r2dbc,
      circe.core.value,
      circe.generic.value,
      circe.genericExtras.value,
    ),
    dependencyOverrides ++= Dependencies.akka.createOverrides(licensed = true),
  ).dependsOn(`akka-components`, `akka-circe`, `akka-components-persistence-projection`, `logstage-elastic`)

lazy val `akka-components-persistence-projection-lagom-api` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("akka-components-persistence-projection-lagom-api"))
  .jvmSettings(libraryDependencies += lagom.scaladslApi)
  .jsSettings(libraryDependencies += lagom.js.scalaDslApi.value)
  .settings(
    libraryDependencies ++= Seq(
      circe.core.value,
      circe.parser.value,
    ),
    idePackagePrefix := Some("net.sc8s.akka.components.persistence.projection.lagom.api")
  )
  .dependsOn(`common-circe`, `lagom-api-circe`, `akka-components-persistence-projection-api`)

lazy val `akka-components-persistence-projection-common` = project
  .settings(
    libraryDependencies ++= Seq(
      akka.persistenceTyped,
      akka.projection.eventsourced,
      akka.clusterShardingTyped,
    ),
    idePackagePrefix := Some("net.sc8s.akka.components.persistence.projection.common")
  )
  .dependsOn(`akka-components`, `akka-components-persistence-projection`, `akka-components-persistence-projection-api`.jvm, `logstage-elastic`)

lazy val `akka-components-persistence-projection-lagom` = project
  .settings(
    libraryDependencies ++= Seq(
      lagom.scaladslServer,
      macwire.macros
    ),
    idePackagePrefix := Some("net.sc8s.akka.components.persistence.projection.lagom")
  )
  .dependsOn(`akka-components`, `akka-components-persistence-projection-lagom-api`.jvm, `akka-components-persistence-projection-common`, `akka-components-lagom`)

lazy val `akka-components-persistence-projection-tapir` = project
  .settings(
    libraryDependencies ++= Seq(
      tapir.core.value,
      tapir.circe.value,
    ),
    idePackagePrefix := Some("net.sc8s.akka.components.persistence.projection.tapir")
  )
  .dependsOn(`akka-components`, `akka-components-persistence-projection-common`)

lazy val `akka-components-persistence-utils` = project
  .settings(
    libraryDependencies ++= Seq(
      akka.persistenceTyped,
      logstage.core.value,
    ),
    idePackagePrefix := Some("net.sc8s.akka.components.persistence.utils")
  )

lazy val `lagom-server-circe` = project
  .settings(
    libraryDependencies ++= Seq(
      lagom.scaladslServer
    ),
    idePackagePrefix := Some("net.sc8s.lagom.circe")
  )
  .dependsOn(`akka-circe`)

lazy val `lagom-server-circe-testkit` = project
  .settings(
    libraryDependencies ++= Seq(
      play.core,
      akka.persistenceTyped,
      akka.persistenceTestkit,
      scalaTest.value,
    ),
    idePackagePrefix := Some("net.sc8s.lagom.circe.testkit")
  )
  .dependsOn(`lagom-server-circe`)

lazy val `lagom-api-circe` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("lagom-api-circe"))
  .jvmSettings(libraryDependencies += lagom.scaladslApi)
  .jsSettings(libraryDependencies += lagom.js.scalaDslApi.value)
  .settings(
    libraryDependencies ++= Seq(
      circe.core.value,
      circe.parser.value,
    ),
    idePackagePrefix := Some("net.sc8s.lagom.circe")
  )
  .dependsOn(`common-circe`)

lazy val `common-circe` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("common-circe"))
  .settings(
    libraryDependencies ++= Seq(
      circe.genericExtras.value,
    ),
    idePackagePrefix := Some("net.sc8s.circe")
  )

lazy val `logstage-elastic` = project
  .settings(
    libraryDependencies ++= Seq(
      slf4j,
      circe.core.value,
      circe.generic.value,
      logstage.core.value,
      logstage.circe.value,
      logstage.toSlf4j,
      scalaTest.value,
    ),
    idePackagePrefix := Some("net.sc8s.logstage.elastic")
  )

lazy val `akka-stream-utils` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("akka-stream-utils"))
  .jvmSettings(
    libraryDependencies ++= Seq(
      akka.stream,
      akka.streamTyped,
      akka.streamTestkit,
      akka.testkitTyped % Test,
    )
  )
  .jsSettings(libraryDependencies += akka.js.stream.value)
  .settings(
    libraryDependencies ++= Seq(
      logstage.core.value,
      cats.core.value,
      scalaTest.value % Test
    ),
    idePackagePrefix := Some("net.sc8s.akka.stream")
  )

lazy val `elastic-core` = (project in file("elastic/core"))
  .settings(
    libraryDependencies ++= Seq(
      scalaTest.value % Test,
      akka.actor,
      akka.typed,
      akka.stream,
      akka.testkitTyped % Test,
      elastic4s.core,
      elastic4s.jsonCirce,
      elastic4s.clientAkka % Test,
      elastic4s.testkit % Test,
      elastic4s.elasticTestFramework % Test,
      elastic4s.httpStreams,
      circe.core.value,
      circe.parser.value,
      circe.generic.value,
      circe.genericExtras.value,
      nameOf,
      macwire.macros,
    ),
    idePackagePrefix := Some("net.sc8s.elastic")
  )
  .dependsOn(`logstage-elastic`, `schevo-circe`.jvm, `akka-stream-utils`.jvm, `akka-components`)

lazy val `elastic-testkit` = (project in file("elastic/testkit"))
  .settings(
    libraryDependencies ++= Seq(
      scalaTest.value,
      akka.testkitTyped,
      elastic4s.core,
      elastic4s.jsonCirce,
      elastic4s.clientAkka,
      elastic4s.testkit,
      elastic4s.elasticTestFramework,
      elastic4s.httpStreams,
      circe.core.value,
      circe.parser.value,
      circe.generic.value,
      circe.genericExtras.value,
      macwire.macros,
      logback.classic % Test,
      logback.core % Test,
    ),
    idePackagePrefix := Some("net.sc8s.elastic.testkit")
  )
  .dependsOn(`elastic-core`, `schevo-circe`.jvm, `akka-components-testkit` % Test)

lazy val `elastic-lagom-api` =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Pure)
    .in(file("elastic/lagom/api"))
    .jvmSettings(libraryDependencies += lagom.scaladslApi)
    .jsSettings(libraryDependencies += lagom.js.scalaDslApi.value)
    .settings(
      idePackagePrefix := Some("net.sc8s.elastic.lagom.api")
    )

lazy val `elastic-lagom-service` = (project in file("elastic/lagom/service"))
  .settings(
    libraryDependencies ++= Seq(
      elastic4s.core,
      elastic4s.clientAkka,
      macwire.macros
    ),
    idePackagePrefix := Some("net.sc8s.elastic.lagom")
  )
  .dependsOn(`elastic-core`, `elastic-lagom-api`.jvm)

// empty project to avoid regeneration in other projects https://github.com/cquiroz/sbt-tzdb/issues/88
lazy val `common-tzdb` = crossProject(JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("common-tzdb"))
  .jsSettings(
    libraryDependencies += scalaJavaTime.value,
    zonesFilter := { (z: String) => z == "Europe/Berlin" || z == "UTC" || z == "GMT" },
  )
  .enablePlugins(ScalaJSPlugin, TzdbPlugin)

lazy val akkaToken = sys.env.getOrElse("AKKA_TOKEN", throw new Exception("AKKA_TOKEN environment variable is required"))

inThisBuild(Seq(
  scalaVersion := scala213,
  organization := "net.sc8s",
  homepage := Some(url("https://github.com/an-tex/sc8s")),
  licenses := List("MIT" -> url("https://opensource.org/licenses/MIT")),
  developers := List(
    Developer(
      "an-tex",
      "Andreas Gabor",
      "andreas@sc8s.net",
      url("https://rob.ag")
    )
  ),
  libraryDependencySchemes ++= Seq(
    "org.scala-lang.modules" %% "scala-java8-compat" % "always",
  ),
  resolvers ++= Seq(
    "antex public" at "https://mymavenrepo.com/repo/zeKhQjbzBED1vIds46Kj/",
    "akka-secure-mvn" at s"https://repo.akka.io/$akkaToken/secure",
    Resolver.url("akka-secure-ivy", url(s"https://repo.akka.io/$akkaToken/secure"))(Resolver.ivyStylePatterns),
  ),
  scmInfo := Some(ScmInfo(url("https://github.com/an-tex/sc8s"), "scm:git:git://github.com/an-tex/sc8s.git")),
  githubWorkflowJavaVersions := Seq(JavaSpec(Adopt, "11.0.13+8")),
  githubWorkflowTargetTags := Seq("*"),
  githubWorkflowEnv += "AKKA_TOKEN" -> "${{ secrets.AKKA_TOKEN}}",
  githubWorkflowPublish := Seq(WorkflowStep.Sbt(
    List("ci-release"),
    env = Map(
      "PGP_PASSPHRASE" -> "${{ secrets.PGP_PASSPHRASE }}",
      "PGP_SECRET" -> "${{ secrets.PGP_SECRET }}",
      "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}",
      "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}",
    )
  )),
  githubWorkflowJobSetup += WorkflowStep.Run(List("docker compose up -d")),
  githubWorkflowPublishTargetBranches := Seq(RefPredicate.StartsWith(Ref.Tag("v"))),
  versionScheme := Some("early-semver"),
  dependencyOverrides ++= Dependencies.overrides ++ Seq(
    // circe-derivation depends on 0.13.0 which is binary compatible to 0.14.x https://github.com/circe/circe-derivation/issues/346
    // needs to be added here instead of Dependencies.overrides due to .value call
    circe.core.value
  ),
  githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17")),
))

Global / excludeLintKeys += idePackagePrefix
Global / onChangedBuildSource := ReloadOnSourceChanges
