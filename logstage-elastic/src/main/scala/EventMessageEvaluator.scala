package net.sc8s.logstage.elastic

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.boolex.EventEvaluatorBase

class EventMessageEvaluator extends EventEvaluatorBase[ILoggingEvent] {
  def evaluate(event: ILoggingEvent): Boolean = {
    event.getMessage.contains("{\"event\":{")
  }
}
