import Dependencies._

lazy val sc8s = (project in file("."))
  .settings(
    skip in publish := true
  )
  .aggregate(
    `akka-circe`,
    `lagom-server-circe`,
    `lagom-server-circe-testkit`,
    `lagom-api-circe`.jvm,
    `lagom-api-circe`.js,
    `schevo`.jvm,
    `schevo`.js,
    `common-circe`.jvm,
    `common-circe`.js,
    `logstage-elastic`
  )

lazy val `schevo` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("schevo"))
  .settings(
    libraryDependencies ++= Seq(
      scalaTest.value % Test,
    )
  )

lazy val `akka-circe` = (project in file("akka-circe"))
  .settings(
    name := "akka-circe",
    libraryDependencies ++= Seq(
      akka.typed,
      akka.stream,
      circe.core.value,
      circe.parser.value,
      circe.generic.value,
      circe.genericExtras.value,
      scalaTest.value % Test,
      akka.testkitTyped % Test,
    )
  ).dependsOn(`common-circe`.jvm)

lazy val `lagom-server-circe` = (project in file("lagom-server-circe"))
  .settings(
    libraryDependencies ++= Seq(
      lagom.scaladslServer
    )
  )
  .dependsOn(`akka-circe`)

lazy val `lagom-server-circe-testkit` = (project in file("lagom-server-circe-testkit"))
  .settings(
    libraryDependencies ++= Seq(
      play.core,
      akka.persistenceTyped,
      akka.persistenceTestkit,
      scalaTest.value
    )
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
    )
  )
  .dependsOn(`common-circe`)

lazy val `common-circe` = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("common-circe"))
  .settings(
    libraryDependencies ++= Seq(
      circe.genericExtras.value,
    )
  )

lazy val `logstage-elastic` = (project in file("logstage-elastic"))
  .settings(
    libraryDependencies ++= Seq(
      slf4j,
      logstage.core.value,
      logstage.circe.value,
      logstage.toSlf4j,
    )
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
))
