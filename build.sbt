import Dependencies._
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
    `akka-components-persistence-projection-lagom-api`.js,
    `akka-components-persistence-projection-lagom-api`.jvm,
    `akka-components-persistence-projection-lagom`,
    `akka-components-persistence-utils`,
    `akka-stream-utils`.js,
    `akka-stream-utils`.jvm,
    `common-circe`.js,
    `common-circe`.jvm,
    `common-tzdb`.js,
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

lazy val `schevo-circe-example-akka` = (project in file("schevo-circe-example-akka"))
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

lazy val `akka-circe` = (project in file("akka-circe"))
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

lazy val `akka-components` = (project in file("akka-components"))
  .settings(
    libraryDependencies ++= Seq(
      akka.persistenceTyped,
      akka.persistenceTestkit,
      chimney.value,
      scalaTest.value,
      scalamock,
      macwire.macros
    ),
    idePackagePrefix := Some("net.sc8s.akka.components")
  )
  .dependsOn(`akka-circe`, `akka-components-persistence-projection`, `lagom-server-circe`, `akka-components-persistence-utils`)

lazy val `akka-components-lagom` = (project in file("akka-components-lagom"))
  .settings(
    libraryDependencies ++= Seq(
      lagom.scaladslServer,
      macwire.macros
    ),
    idePackagePrefix := Some("net.sc8s.akka.components.lagom")
  )
  .dependsOn(`akka-components`)

lazy val `akka-components-testkit` = (project in file("akka-components-testkit"))
  .settings(
    libraryDependencies ++= Seq(
      scalaTest.value,
      scalamock,
      akka.testkitTyped % Test,
      akka.persistenceTestkit % Test,
      akka.projection.testKit,
      macwire.macros
    ),
    idePackagePrefix := Some("net.sc8s.akka.components.testkit")
  )
  .dependsOn(`akka-components`, `lagom-server-circe-testkit`, `akka-components-persistence-projection-cassandra`)

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
    idePackagePrefix := Some("net.sc8s.akka.components.persistence.lagom.api")
  )
  .dependsOn(`common-circe`, `lagom-api-circe`)

lazy val `akka-components-persistence-cassandra-lagom` = (project in file("akka-components-persistence-cassandra-lagom"))
  .settings(
    libraryDependencies ++= Seq(
      lagom.scaladslServer,
      macwire.macros,
      akka.persistenceCassandra,
    ),
    idePackagePrefix := Some("net.sc8s.akka.components.persistence.lagom")
  )
  .dependsOn(`akka-components`, `akka-components-persistence-cassandra-lagom-api`.jvm)

lazy val `akka-components-persistence-projection` = (project in file("akka-components-persistence-projection"))
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

lazy val `akka-components-persistence-projection-cassandra` = (project in file("akka-components-persistence-projection-cassandra"))
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

lazy val `akka-components-persistence-projection-r2dbc` = (project in file("akka-components-persistence-projection-r2dbc"))
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

lazy val `akka-components-persistence-projection-lagom` = (project in file("akka-components-persistence-projection-lagom"))
  .settings(
    libraryDependencies ++= Seq(
      lagom.scaladslServer,
      macwire.macros
    ),
    idePackagePrefix := Some("net.sc8s.akka.components.persistence.projection.lagom")
  )
  .dependsOn(`akka-components`, `akka-components-persistence-projection-lagom-api`.jvm)

lazy val `akka-components-persistence-utils` = (project in file("akka-components-persistence-utils"))
  .settings(
    libraryDependencies ++= Seq(
      akka.persistenceTyped,
      logstage.core.value,
    ),
    idePackagePrefix := Some("net.sc8s.akka.components.persistence.utils")
  )

lazy val `lagom-server-circe` = (project in file("lagom-server-circe"))
  .settings(
    libraryDependencies ++= Seq(
      lagom.scaladslServer
    ),
    idePackagePrefix := Some("net.sc8s.lagom.circe")
  )
  .dependsOn(`akka-circe`)

lazy val `lagom-server-circe-testkit` = (project in file("lagom-server-circe-testkit"))
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

lazy val `logstage-elastic` = (project in file("logstage-elastic"))
  .settings(
    libraryDependencies ++= Seq(
      slf4j,
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

// empty project to avoid regeneration in other projects https://github.com/cquiroz/sbt-tzdb/issues/88
lazy val `common-tzdb` = crossProject(JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("common-tzdb"))
  .jsSettings(
    libraryDependencies += scalaJavaTime.value,
    zonesFilter := { (z: String) => z == "Europe/Berlin" || z == "UTC" || z == "GMT" },
  )
  .enablePlugins(ScalaJSPlugin, TzdbPlugin)

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
  scmInfo := Some(ScmInfo(url("https://github.com/an-tex/sc8s"), "scm:git:git://github.com/an-tex/sc8s.git")),
  githubWorkflowJavaVersions := Seq(JavaSpec(Adopt, "11.0.13+8")),
  githubWorkflowTargetTags := Seq("*"),
  githubWorkflowPublish := Seq(WorkflowStep.Sbt(
    List("ci-release"),
    env = Map(
      "PGP_PASSPHRASE" -> "${{ secrets.PGP_PASSPHRASE }}",
      "PGP_SECRET" -> "${{ secrets.PGP_SECRET }}",
      "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}",
      "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}"
    )
  )),
  githubWorkflowPublishTargetBranches := Seq(RefPredicate.StartsWith(Ref.Tag("v"))),
  versionScheme := Some("early-semver"),
  dependencyOverrides ++= Dependencies.overrides ++ Seq(
    // circe-derivation depends on 0.13.0 which is binary compatible to 0.14.x https://github.com/circe/circe-derivation/issues/346
    // needs to be added here instead of Dependencies.overrides due to .value call
    circe.core.value
  ),
))

Global / excludeLintKeys += idePackagePrefix