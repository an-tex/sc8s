package net.sc8s.logstage.elastic

import izumi.logstage.api.IzLogger
import izumi.logstage.api.Log.Level
import izumi.logstage.sink.ConsoleSink.SimpleConsoleSink
import org.scalatest.wordspec.AnyWordSpecLike

class LoggingSpec extends AnyWordSpecLike with Logging {
  "Logging" should {
    "extend IzLogger" in {
      val consoleLogger = IzLogger(Level.Trace, SimpleConsoleSink)
      consoleLogger.traceT("myTag")
      consoleLogger.debugT("myTag")
      consoleLogger.infoT("myTag")
      consoleLogger.warnT("myTag")
      consoleLogger.errorT("myTag")
      consoleLogger.critT("myTag")
     
      consoleLogger.infoT("myTag", s"prefix ${"value" -> "another"} suffix")
    }
  }
}
