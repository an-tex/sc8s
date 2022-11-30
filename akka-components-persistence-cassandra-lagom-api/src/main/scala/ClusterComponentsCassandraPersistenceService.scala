package net.sc8s.akka.components.persistence.cassandra.lagom.api

import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.transport.Method
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}

trait ClusterComponentsCassandraPersistenceService extends Service {
  val apiPrefix: String

  def deleteSingletonEntity(name: String): ServiceCall[NotUsed, Done]

  def deleteShardedEntities(name: String): ServiceCall[NotUsed, Done]

  abstract override def descriptor = {
    import Service._
    super.descriptor.addCalls(
      restCall(Method.DELETE, s"$apiPrefix/entity/singleton/:name", deleteSingletonEntity _),
      restCall(Method.DELETE, s"$apiPrefix/entity/sharded/:name", deleteShardedEntities _),
    )
  }
}
