package net.sc8s.akka.stream

import akka.stream.scaladsl.{Flow, Source}

/**
 * Workaround for https://doc.akka.io/docs/akka-http/current/client-side/websocket-support.html?language=scala#half-closed-websockets
 *
 * The callers source doesn't complete, hence the websocket is kept open until the sender finishes.
 * The callee still needs to know when the callers Source has completed, so a Wrapper is used indicating the end of stream with an empty Element.
 *
 * You also need to define a serializer for the wrapped element, e.g:
 * implicit val wrapperFormatter : play.api.libs.json.Format[Wrapper[Int]] = play.api.libs.json.Json.format
 */
object HalfClosedWebsocketSupport {
  case class Wrapper[T](value: Option[T])

  def source[T, S](source: Source[T, S]) = source
    .map(e => Wrapper(Some(e)))
    .concat(Source.single(Wrapper(Option.empty[T])))
    .concat(Source.maybe)

  def flow[T] = Flow[Wrapper[T]]
    .takeWhile(_.value.isDefined)
    .map(_.value.get)
}
