package net.sc8s.akka.stream

import akka.event.LoggingAdapter
import izumi.logstage.api.IzLogger
import izumi.logstage.api.Log.Level

class IzLoggingAdapter(izLogger: IzLogger) extends LoggingAdapter {
  override def isErrorEnabled = izLogger.acceptable(Level.Error)

  override def isWarningEnabled = izLogger.acceptable(Level.Warn)

  override def isInfoEnabled = izLogger.acceptable(Level.Info)

  override def isDebugEnabled = izLogger.acceptable(Level.Debug)

  override protected def notifyError(message: String) = izLogger.error(s"${message -> null}")

  override protected def notifyError(cause: Throwable, message: String) = izLogger.error(s"${message -> null} $cause")

  override protected def notifyWarning(message: String) = izLogger.warn(s"${message -> null}")

  override protected def notifyInfo(message: String) = izLogger.info(s"${message -> null}")

  override protected def notifyDebug(message: String) = izLogger.debug(s"${message -> null}")
}
