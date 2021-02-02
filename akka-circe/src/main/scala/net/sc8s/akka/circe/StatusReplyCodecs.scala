package net.sc8s.akka.circe

import akka.Done
import akka.pattern.StatusReply
import io.circe.{Codec, Decoder, Encoder}

import scala.reflect.ClassTag

object StatusReplyCodecs extends StatusReplyCodecs

// noop codecs as akka takes care of it internally. just to make e.g. ActorRef[...] coding implicits happy
trait StatusReplyCodecs {
  private def noopEncoder[T] = Encoder.instance[T](_ => throw new Exception("noop"))

  private def noopDecoder[T] = Decoder.instance[T](_ => throw new Exception("noop"))

  private def noopCodec[T]: Codec[T] = Codec.from(noopDecoder, noopEncoder)

  implicit def statusReplyCodec[T: ClassTag](implicit decoder: Decoder[T], encoder: Encoder[T]): Codec[StatusReply[T]] = noopCodec

  implicit val doneCodec: Codec[Done] = noopCodec
}
