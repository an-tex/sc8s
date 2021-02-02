# sc8s

### akka-circe
[Akka](https://akka.io) serializers using [circe](https://github.com/circe/circe). Includes serializers for `ActorRefs` and `StreamRefs`

### lagom-circe
[Lagom](https://www.lagomframework.com/) components and message serializers using [circe](https://github.com/circe/circe).

## Usage

Add the following to your `build.sbt`:

```sbt
resolvers += "sc8s packages" at "https://maven.pkg.github.com/an-tex/sc8s"

libraryDependencies ++= Seq(
  "net.sc8s" %% "akka-circe" % VERSION,
  "net.sc8s" %% "lagom-circe" % VERSION
)
```