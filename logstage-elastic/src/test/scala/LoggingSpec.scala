package net.sc8s.logstage.elastic

import izumi.logstage.api.IzLogger
import izumi.logstage.api.Log.Level
import izumi.logstage.sink.ConsoleSink
import izumi.logstage.sink.ConsoleSink.SimpleConsoleSink
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
    "test json logging" in {
      val elasticLogger = IzLogger(Level.Debug, ConsoleSink(LogstageCirceElasticRenderingPolicy("loggerClass.prefix")))()
      elasticLogger.withCustomContext("myContext" -> "value0").info(s"prefix ${"value1" -> "field1"} middle ${"value2" -> "field2"} suffix")
    }
  }
}
