package akka.serialization.circe

import akka.actor.ExtendedActorSystem
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import akka.serialization.Serialization

object SerializationHelper {
  def setCurrentTransportInformation(system: ActorSystem[_]) =
    Serialization.currentTransportInformation.value = system.toClassic.asInstanceOf[ExtendedActorSystem].provider.serializationInformation
}
