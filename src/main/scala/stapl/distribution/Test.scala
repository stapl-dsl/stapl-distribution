/**
 *    Copyright 2015 KU Leuven Research and Developement - iMinds - Distrinet
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    Administrative Contact: dnet-project-office@cs.kuleuven.be
 *    Technical Contact: maarten.decat@cs.kuleuven.be
 *    Author: maarten.decat@cs.kuleuven.be
 */
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