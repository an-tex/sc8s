import Dependencies._

lazy val sc8s = (project in file("."))
  .settings(
    publish / skip := true
  )
  .aggregate(
    `akka-circe`,
    `akka-projection-utils`,
    `akka-projection-utils-api`.js,
    `akka-projection-utils-api`.jvm,
    `common-circe`.js,
    `common-circe`.jvm,
    `lagom-api-circe`.js,
    `lagom-api-circe`.jvm,
    `lagom-server-circe-testkit`,
    `lagom-server-circe`,
    `logstage-elastic`,
    `schevo`.js,
    `schevo`.jvm,
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

lazy val `akka-projection-utils` = (project in file("akka-projection-utils"))
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
    idePackagePrefix := Some("net.sc8s.akka.projection")
  ).dependsOn(`akka-circe`, `akka-projection-utils-api`.jvm, `logstage-elastic`)

lazy val `akka-projection-utils-api` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("akka-projection-utils-api"))
  .settings(
    libraryDependencies ++= Seq(
      circe.core.value,
      circe.generic.value,
      circe.genericExtras.value
    ),
    idePackagePrefix := Some("net.sc8s.akka.projection.api")
  ).dependsOn(`common-circe`)

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
      scalaTest.value
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
    ),
    idePackagePrefix := Some("net.sc8s.logstage.elastic")
  )

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
  scmInfo := Some(ScmInfo(url("https://github.com/an-tex/sc8s"), "scm:git:git://github.com/an-tex/sc8s.git")),
  githubWorkflowJavaVersions := Seq("adopt@1.11"),
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
  versionScheme := Some("early-semver")
))

Global / excludeLintKeys += idePackagePrefix