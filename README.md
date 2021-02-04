# sc8s

### akka-circe
[Akka](https://akka.io) serializers using [circe](https://github.com/circe/circe). Includes serializers for `ActorRefs` and `StreamRefs`

### lagom-api-circe
[Lagom](https://www.lagomframework.com/) message serializers using [circe](https://github.com/circe/circe).

### lagom-server-circe
[Lagom](https://www.lagomframework.com/) components using `akka-circe`

### lagom-server-circe-testkit
[Lagom](https://www.lagomframework.com/) [ScalaTest](https://www.scalatest.org/) helpers 

## Usage

Add the following to your `build.sbt`:

```sbt
val sc8sVersion = "VERSION"
libraryDependencies ++= Seq(
  "net.sc8s" %% "akka-circe" % sc8sVersion,
  "net.sc8s" %% "lagom-api-circe" % sc8sVersion,
  "net.sc8s" %% "lagom-server-circe" % sc8sVersion,
  "net.sc8s" %% "lagom-server-circe-testkit" % sc8sVersion % Test
)
```

You can find the latest version here on Github under [Releases](https://github.com/an-tex/sc8s/releases) or on [Maven Central](https://search.maven.org/search?q=g:net.sc8s)