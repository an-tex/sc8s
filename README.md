[![maven-central-version](https://img.shields.io/maven-central/v/net.sc8s/akka-circe_2.13)](https://search.maven.org/search?q=g:net.sc8s)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)

# sc8s

### akka-circe
[Akka](https://akka.io) serializers using [circe](https://github.com/circe/circe). Includes serializers for `ActorRefs` and `StreamRefs`

### lagom-api-circe
[Lagom](https://www.lagomframework.com/) message serializers using [circe](https://github.com/circe/circe).

### lagom-server-circe
[Lagom](https://www.lagomframework.com/) components using `akka-circe`

### lagom-server-circe-testkit
[Lagom](https://www.lagomframework.com/) [ScalaTest](https://www.scalatest.org/) helpers 

### logstage-elastic
Rendering policy for [Logstage](https://izumi.7mind.io/logstage/) for enhanced [ElasticSearch](https://elastic.co/) compability

## Usage

Add the following to your `build.sbt`:

`val sc8sVersion =` [![CHANGEME](https://img.shields.io/maven-central/v/net.sc8s/akka-circe_2.13?color=%23aaaaaa&label=%20&style=flat-square)](https://search.maven.org/search?q=g:net.sc8s)
```sbt
libraryDependencies ++= Seq(
  "net.sc8s" %% "akka-circe" % sc8sVersion,
  "net.sc8s" %% "lagom-api-circe" % sc8sVersion,
  "net.sc8s" %% "lagom-server-circe" % sc8sVersion,
  "net.sc8s" %% "lagom-server-circe-testkit" % sc8sVersion % Test,
  "net.sc8s" %% "logstage-elastic" % sc8sVersion,
)
```
