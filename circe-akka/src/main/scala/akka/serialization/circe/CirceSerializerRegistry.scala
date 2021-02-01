package akka.serialization.circe

import akka.actor.ExtendedActorSystem
import akka.actor.setup.ActorSystemSetup
import akka.serialization.{SerializationSetup, SerializerDetails}

import scala.collection.immutable

abstract class CirceSerializerRegistry {
  def serializers: immutable.Seq[CirceSerializer[_]]

  final def ++(other: CirceSerializerRegistry): CirceSerializerRegistry = {
    val self = this
    new CirceSerializerRegistry {
      override def serializers = self.serializers ++ other.serializers
    }
  }
}

object CirceSerializerRegistry {

  def serializerDetailsFor(system: ExtendedActorSystem, registry: CirceSerializerRegistry): SerializerDetails = {
    SerializerDetails(
      "circe",
      new CirceJsonSerializer(system, registry),
      registry.serializers.map(_.entityClass)
    )
  }

  def serializationSetupFor(registry: CirceSerializerRegistry): SerializationSetup = SerializationSetup(system => Vector(serializerDetailsFor(system, registry)))

  def actorSystemSetupFor(registry: CirceSerializerRegistry): ActorSystemSetup =
    ActorSystemSetup(serializationSetupFor(registry))
}