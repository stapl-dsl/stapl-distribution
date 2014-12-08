package stapl.distribution

import akka.actor.ActorRef
import stapl.core.Permit
import stapl.core.Result
import akka.actor.ActorSystem
import scala.concurrent.duration._
import scala.util.{ Success, Failure }
import akka.actor.Props
import akka.actor.actorRef2Scala
import scala.math.BigInt.int2bigInt
import stapl.distribution.components.Foreman
import com.typesafe.config.ConfigFactory
import stapl.examples.policies.EhealthPolicy
import stapl.core.AbstractPolicy
import stapl.distribution.policies.ConcurrencyPolicies
import stapl.distribution.components.ClientCoordinatorProtocol
import com.hazelcast.config.Config
import stapl.distribution.db.AttributeMapStore
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.IMap
import stapl.core.AttributeContainerType
import stapl.distribution.db.HazelcastAttributeDatabaseConnection
import com.hazelcast.config.MapConfig
import com.hazelcast.config.MapStoreConfig
import stapl.core.RESOURCE

object Test {
  def main(args: Array[String]) {
    // set up hazelcast db connection
    val MAP_NAME = "stapl-attributes"
    val cfg = new Config()
    cfg.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false)
    val mapCfg = new MapConfig(MAP_NAME)
    mapCfg.setMapStoreConfig(new MapStoreConfig()
      .setEnabled(true)
      .setImplementation(new AttributeMapStore("127.0.0.1", 3306, "stapl-attributes", "root", "root")))
    cfg.addMapConfig(mapCfg)
    val hazelcast = Hazelcast.newHazelcastInstance(cfg)
    val db = new HazelcastAttributeDatabaseConnection(hazelcast.getMap(MAP_NAME))
    
    val result = db.getStringAttribute("patientstatus:of:maarten", RESOURCE, "type_")
    
    println(result)
  }
}