package net.sc8s.logstage.elastic

import io.circe.{Codec, derivation}
import izumi.logstage.api.IzLogger
import izumi.logstage.api.Log.Level
import izumi.logstage.sink.ConsoleSink
import izumi.logstage.sink.ConsoleSink.SimpleConsoleSink
import logstage.LogstageCodec
import logstage.circe.LogstageCirceCodec
import net.sc8s.logstage.elastic.LoggingSpec.CaseClass
import org.scalatest.wordspec.AnyWordSpecLike

class LoggingSpec extends AnyWordSpecLike {
  "Logging" should {
    "extend IzLogger with tag log helpers" in {
      val consoleLogger = IzLogger(Level.Trace, SimpleConsoleSink)
      import Logging._
      consoleLogger.traceT("myTag")
      consoleLogger.debugT("myTag")
      consoleLogger.infoT("myTag")
      consoleLogger.warnT("myTag")
      consoleLogger.errorT("myTag")
      consoleLogger.critT("myTag")

      consoleLogger.infoT("myTag", s"prefix ${"value" -> "another"} suffix")
    }
    "json logging" in {
      val caseClass = CaseClass(3)
      val elasticLogger = IzLogger(Level.Debug, ConsoleSink(LogstageCirceElasticRenderingPolicy("loggerClass.prefix")))()
      elasticLogger.withCustomContext("myContext" -> "value0").info(s"prefix ${"value1" -> "field1"} middle ${"value2" -> "field2"} $caseClass suffix")
    }
    "case class json logging" in {
      val caseClass = CaseClass(3)
      val elasticLogger = IzLogger(Level.Debug, ConsoleSink(LogstageCirceElasticRenderingPolicy("prefix")))()
      elasticLogger.info(s"$caseClass")
    }
    "typeSuffixes" in {
      val elasticLogger = IzLogger(Level.Debug, ConsoleSink(LogstageCirceElasticRenderingPolicy("loggerClass.prefix")))()

      val numberInt = 7
      val numberLong = 13L
      val numberDouble = 17.89

      val boolean = true

      val string = "moin"

      val list = Seq(3, 5)

      val mapObject = Map("key" -> "value")

      val caseClassObject = CaseClass(3)

      val elasticLoggerWithContext = elasticLogger.withCustomContext(
        "ctxNumberInt" -> numberInt,
        "ctxNumberLong" -> numberLong,
        "ctxNumberDouble" -> numberDouble,
        "ctxBoolean" -> boolean,
        "ctxString" -> string,
        "ctxList" -> list,
        "ctxMapObject" -> mapObject,
        "ctxCaseClassObject" -> caseClassObject,
        "boolean" -> false // will be overwritten by message value in event field
      )

      elasticLoggerWithContext.info(s"$numberInt $numberLong $numberDouble $boolean $string $list $mapObject $caseClassObject")

      elasticLoggerWithContext.info(s"${"withTag" -> "tag"} $numberInt $numberLong $numberDouble $boolean $string $list $mapObject $caseClassObject")
    }
    "legacy logger" in {
      val elasticLogger = IzLogger(Level.Debug, ConsoleSink(LogstageCirceElasticRenderingPolicy("loggerClass.prefix", legacyNameNormalisation = true)))()
      elasticLogger.info(s"${true -> "testWithCamelCase"}")
    }
    "truncate string values" in {
      val truncateTo = 128
      val elasticLogger = IzLogger(Level.Debug, ConsoleSink(LogstageCirceElasticRenderingPolicy("prefix", truncateStringValues = Some(truncateTo))))()
      val longString = "a" * truncateTo + "DROPME"
      elasticLogger.info(s"this will be a $longString")
    }
  }
}

object LoggingSpec {
  case class CaseClass(x: Int)
  object CaseClass {
    implicit val circeCodec: Codec[CaseClass] = derivation.deriveCodec[CaseClass]
    implicit val logstageCodec: LogstageCodec[CaseClass] = LogstageCirceCodec.derived[CaseClass]
  }
}