import sbtghactions.GenerativePlugin.autoImport.githubWorkflowJavaVersions
import sbtghpackages.GitHubPackagesPlugin.autoImport.githubRepository

inThisBuild(List(
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
  )
))

lazy val sc8s = (project in file("."))
  .settings(
    publishArtifact := false
  )
  .aggregate(
    `akka-circe`,
    `lagom-circe`
  )

lazy val `akka-circe` = (project in file("akka-circe"))
  .settings(
    name := "akka-circe",
    libraryDependencies ++= Seq(
      Dependencies.akka.typed,
      Dependencies.akka.stream,
      Dependencies.circe.core.value,
      Dependencies.circe.parser.value,
      Dependencies.scalaTest.value % Test,
      Dependencies.akka.testkitTyped % Test,
      Dependencies.circe.generic.value % Test,
      Dependencies.circe.genericExtras.value % Test,
    )
  )

lazy val `lagom-circe` = (project in file("lagom-circe"))
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.lagom.scaladslServer
    )
  )
  .dependsOn(`akka-circe`)

inThisBuild(Seq(
  scalaVersion := Dependencies.scala213,
  envFileName := ".envrc",
  githubOwner := "an-tex",
  githubRepository := "sc8s",
  githubWorkflowJavaVersions := Seq("adopt@1.11"),
  githubWorkflowTargetTags := Seq("*"),
  githubWorkflowPublishTargetBranches := Seq(RefPredicate.StartsWith(Ref.Tag("v")))
))
