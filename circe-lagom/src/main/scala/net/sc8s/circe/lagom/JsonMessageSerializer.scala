package net.sc8s.circe.lagom

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.lightbend.lagom.scaladsl.api.deser.MessageSerializer.{NegotiatedDeserializer, NegotiatedSerializer}
import com.lightbend.lagom.scaladsl.api.deser.{MessageSerializer, StreamedMessageSerializer, StrictMessageSerializer}
import com.lightbend.lagom.scaladsl.api.transport.{DeserializationException, MessageProtocol}
import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}

import scala.collection.immutable

object JsonMessageSerializer extends LowPriorityJsonMessageSerializerImplicits {

  private[this] val messageProtocol = MessageProtocol(Some("application/json"), None, None)

  implicit val strictJsonMessageSerializer: StrictMessageSerializer[Json] = new StrictMessageSerializer[Json] {

    override def acceptResponseProtocols = Vector(messageProtocol)

    private final val serializer = new NegotiatedSerializer[Json, ByteString] {

      override def protocol = messageProtocol

      // dropNullValues can cause issues with Map[_,Option[_]] https://github.com/circe/circe/issues/1544
      override def serialize(message: Json) = ByteString.fromString(message.noSpaces, utf8)
    }

    private final val deserializer = new NegotiatedDeserializer[Json, ByteString] {
      override def deserialize(wire: ByteString) = {
        parse(wire.decodeString(utf8))
          .fold(
            parsingFailure => throw DeserializationException(parsingFailure),
            identity
          )
      }
    }

    override def serializerForRequest = serializer

    override def deserializer(protocol: MessageProtocol) = deserializer

    override def serializerForResponse(acceptedMessageProtocols: immutable.Seq[MessageProtocol]) = serializer
  }

  implicit val streamedJsonMessageSerializer: StreamedMessageSerializer[Json] = new StreamedMessageSerializer[Json] {

    override def acceptResponseProtocols = Vector(messageProtocol)

    private final val serializer = new NegotiatedSerializer[Source[Json, NotUsed], Source[ByteString, NotUsed]] {

      override def protocol = messageProtocol

      // dropNullValues can cause issues with Map[_,Option[_]] https://github.com/circe/circe/issues/1544
      override def serialize(message: Source[Json, NotUsed]) = message.map(json => ByteString.fromString(json.noSpaces, utf8))
    }

    private final val deserializer = new NegotiatedDeserializer[Source[Json, NotUsed], Source[ByteString, NotUsed]] {
      override def deserialize(wire: Source[ByteString, NotUsed]) = wire.map(byteString => parse(byteString.decodeString(utf8))
        .fold(
          parsingFailure => throw DeserializationException(parsingFailure),
          identity
        )
      )
    }

    override def serializerForRequest = serializer

    override def deserializer(protocol: MessageProtocol) = deserializer

    override def serializerForResponse(acceptedMessageProtocols: immutable.Seq[MessageProtocol]) = serializer
  }

  private val utf8 = "utf-8"
}

trait LowPriorityJsonMessageSerializerImplicits {

  implicit def jsonMessageSerializer[Message](
                                               implicit jsonMessageSerializer: MessageSerializer[Json, ByteString],
                                               encoder: Encoder[Message],
                                               decoder: Decoder[Message],
                                             ): StrictMessageSerializer[Message] = new StrictMessageSerializer[Message] {
    private class JsonEnDecoderSerializer(jsonSerializer: NegotiatedSerializer[Json, ByteString])
      extends NegotiatedSerializer[Message, ByteString] {
      override def protocol: MessageProtocol = jsonSerializer.protocol

      override def serialize(message: Message): ByteString = jsonSerializer.serialize(message.asJson(encoder))
    }

    private class JsonEnDecoderDeserializer(jsonDeserializer: NegotiatedDeserializer[Json, ByteString])
      extends NegotiatedDeserializer[Message, ByteString] {
      override def deserialize(wire: ByteString): Message = {
        val json = jsonDeserializer.deserialize(wire)
        json.as[Message] match {
          case Left(value) => throw DeserializationException(value)
          case Right(value) => value
        }
      }
    }

    override def acceptResponseProtocols: immutable.Seq[MessageProtocol] =
      jsonMessageSerializer.acceptResponseProtocols

    override def deserializer(protocol: MessageProtocol): NegotiatedDeserializer[Message, ByteString] =
      new JsonEnDecoderDeserializer(jsonMessageSerializer.deserializer(protocol))

    override def serializerForResponse(
                                        acceptedMessageProtocols: immutable.Seq[MessageProtocol]
                                      ): NegotiatedSerializer[Message, ByteString] =
      new JsonEnDecoderSerializer(jsonMessageSerializer.serializerForResponse(acceptedMessageProtocols))

    override def serializerForRequest: NegotiatedSerializer[Message, ByteString] =
      new JsonEnDecoderSerializer(jsonMessageSerializer.serializerForRequest)
  }

  implicit def sourceMessageSerializer[Message](
                                                 implicit delegate: MessageSerializer[Message, ByteString]
                                               ): StreamedMessageSerializer[Message] = new StreamedMessageSerializer[Message] {
    private class SourceSerializer(delegate: NegotiatedSerializer[Message, ByteString])
      extends NegotiatedSerializer[Source[Message, NotUsed], Source[ByteString, NotUsed]] {
      override def protocol: MessageProtocol = delegate.protocol

      override def serialize(messages: Source[Message, NotUsed]) = messages.map(delegate.serialize)
    }

    private class SourceDeserializer(delegate: NegotiatedDeserializer[Message, ByteString])
      extends NegotiatedDeserializer[Source[Message, NotUsed], Source[ByteString, NotUsed]] {
      override def deserialize(wire: Source[ByteString, NotUsed]) = wire.map(delegate.deserialize)
    }

    override def acceptResponseProtocols: immutable.Seq[MessageProtocol] = delegate.acceptResponseProtocols

    override def deserializer(
                               protocol: MessageProtocol
                             ): NegotiatedDeserializer[Source[Message, NotUsed], Source[ByteString, NotUsed]] =
      new SourceDeserializer(delegate.deserializer(protocol))

    override def serializerForResponse(
                                        acceptedMessageProtocols: immutable.Seq[MessageProtocol]
                                      ): NegotiatedSerializer[Source[Message, NotUsed], Source[ByteString, NotUsed]] =
      new SourceSerializer(delegate.serializerForResponse(acceptedMessageProtocols))

    override def serializerForRequest: NegotiatedSerializer[Source[Message, NotUsed], Source[ByteString, NotUsed]] =
      new SourceSerializer(delegate.serializerForRequest)
  }
}
