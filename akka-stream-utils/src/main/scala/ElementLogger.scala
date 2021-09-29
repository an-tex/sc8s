package net.sc8s.akka.stream

import akka.stream.scaladsl.Flow
import izumi.fundamentals.platform.language.CodePositionMaterializer
import izumi.logstage.api.{IzLogger, Log}

object ElementLogger {
  def apply[T](
                messageExtractor: T => Log.Message = { element: T => Log.Message(s"${"nextElement" -> "tag"} $element") },
                logLevel: Log.Level = Log.Level.Debug
              )(implicit log: IzLogger, pos: CodePositionMaterializer): Flow[T, T, _] = {
    Flow[T].wireTap(element =>
      log.log(logLevel)(messageExtractor(element))
    )
  }
}