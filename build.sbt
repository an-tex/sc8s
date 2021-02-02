import sbtghpackages.GitHubPackagesPlugin.autoImport.githubRepository

lazy val sc8s = (project in file("."))
  .aggregate(
    circeAkka,
    circeLagom
  )

lazy val circeAkka = (project in file("circe-akka"))
  .settings(
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

lazy val circeLagom = (project in file("circe-lagom"))
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.lagom.scaladslServer
    )
  )
  .dependsOn(circeAkka)

Global / organization := "sc8s.net"

inThisBuild(Seq(
  scalaVersion := Dependencies.scala213,
  githubOwner := "an-text",
  githubRepository := "sc8s",
  githubWorkflowPublishTargetBranches := Nil,
  envFileName := ".envrc",
  githubWorkflowJavaVersions := Seq("adopt@1.11")
))
