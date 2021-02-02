lazy val sc8s = (project in file("."))
  .settings(
    skip in publish := true
  )
  .aggregate(
    `akka-circe`,
    `lagom-circe`,
    `lagom-circe-testkit`
  )

lazy val `akka-circe` = (project in file("akka-circe"))
  .settings(
    name := "akka-circe",
    libraryDependencies ++= Seq(
      Dependencies.akka.typed,
      Dependencies.akka.stream,
      Dependencies.circe.core.value,
      Dependencies.circe.parser.value,
      Dependencies.circe.generic.value,
      Dependencies.circe.genericExtras.value,
      Dependencies.scalaTest.value % Test,
      Dependencies.akka.testkitTyped % Test,
    )
  )

lazy val `lagom-circe` = (project in file("lagom-circe"))
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.lagom.scaladslServer
    )
  )
  .dependsOn(`akka-circe`)

lazy val `lagom-circe-testkit` = (project in file("lagom-circe-testkit"))
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.play.core,
      Dependencies.akka.persistenceTyped,
      Dependencies.akka.persistenceTestkit,
      Dependencies.scalaTest.value
    )
  )
  .dependsOn(`lagom-circe`)

inThisBuild(Seq(
  scalaVersion := Dependencies.scala213,
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
