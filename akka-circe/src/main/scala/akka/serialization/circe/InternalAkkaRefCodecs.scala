// needs to reside here due to protected access to akka.serialization.Serialization.currentTransportInformation
package akka.serialization.circe

import akka.actor.ExtendedActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, ActorRefResolver}
import akka.serialization.Serialization
import akka.stream.{SinkRef, SourceRef, StreamRefResolver}
import io.circe.{Decoder, Encoder, HCursor, Json}

trait InternalAkkaRefCodecs {
  implicit def actorRefEncoder[T: Encoder]: Encoder[ActorRef[T]] = (a: ActorRef[T]) => Json.fromString(ActorRefResolver(currentSystem().toTyped).toSerializationFormat(a))

  implicit def actorRefDecoder[T: Decoder]: Decoder[ActorRef[T]] = (c: HCursor) => c.value.as[String].map(ref => ActorRefResolver(currentSystem().toTyped).resolveActorRef(ref))

  implicit def sourceRefEncoder[T: Encoder]: Encoder[SourceRef[T]] = (a: SourceRef[T]) => Json.fromString(StreamRefResolver(currentSystem().toTyped).toSerializationFormat(a))

  implicit def sourceRefDecoder[T: Decoder]: Decoder[SourceRef[T]] = (c: HCursor) => c.value.as[String].map(ref => StreamRefResolver(currentSystem().toTyped).resolveSourceRef(ref))

  implicit def sinkRefEncoder[T: Encoder]: Encoder[SinkRef[T]] = (a: SinkRef[T]) => Json.fromString(StreamRefResolver(currentSystem().toTyped).toSerializationFormat(a))

  implicit def sinkRefDecoder[T: Decoder]: Decoder[SinkRef[T]] = (c: HCursor) => c.value.as[String].map(ref => StreamRefResolver(currentSystem().toTyped).resolveSinkRef(ref))

  private[circe] def currentSystem(): ExtendedActorSystem = {
    Serialization.currentTransportInformation.value match {
      case null =>
        throw new IllegalStateException(
          "Can't access current ActorSystem, Serialization.currentTransportInformation was not set.")
      case Serialization.Information(_, system) => system.asInstanceOf[ExtendedActorSystem]
    }
  }
}
