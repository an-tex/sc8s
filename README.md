# sc8s

### akka-circe
[Akka](https://akka.io) serializers using [circe](https://github.com/circe/circe). Includes serializers for `ActorRefs` and `StreamRefs`

### lagom-circe
[Lagom](https://www.lagomframework.com/) components and message serializers using [circe](https://github.com/circe/circe).

## Usage

Add the following to your `build.sbt`:

```sbt
libraryDependencies ++= Seq(
  "net.sc8s" %% "akka-circe" % VERSION,
  "net.sc8s" %% "lagom-circe" % VERSION
)
```

You can find the latest version here on Github under [Releases](https://github.com/an-tex/sc8s/releases) or on [Maven Central](https://search.maven.org/search?q=g:net.sc8s)