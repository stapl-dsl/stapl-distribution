package stapl.distribution

import stapl.distribution.db.HazelcastAttributeDatabaseConnection
import com.hazelcast.config.Config
import stapl.distribution.db.AttributeMapStore
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.IMap
import stapl.core.AttributeContainerType
import stapl.distribution.db.HazelcastAttributeDatabaseConnection
import com.hazelcast.config.MapConfig
import com.hazelcast.config.MapStoreConfig
import stapl.core.SUBJECT
import stapl.distribution.util.ThroughputStatistics

object Master extends App {

  val MAP_NAME = "stapl-attributes"
  val cfg = new Config();
  cfg.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
  cfg.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true);
  cfg.getNetworkConfig().getJoin().getTcpIpConfig().addMember("127.0.0.1");
  val mapCfg = new MapConfig(MAP_NAME)
  mapCfg.setMapStoreConfig(new MapStoreConfig().setEnabled(true).setImplementation(
    new AttributeMapStore("localhost", 3306, "stapl-attributes", "root", "root")))
  cfg.addMapConfig(mapCfg)
  val instance = Hazelcast.newHazelcastInstance(cfg);
  val hazelcast = instance.getMap(MAP_NAME)

  println(hazelcast.get(("patient:maarten", SUBJECT, "roles"))) // should give List(patient)

}

object Slave extends App {

  // test whether we use the map set up by the Master without
  // declaring all its details
  val MAP_NAME = "stapl-attributes"
  val cfg = new Config();
  cfg.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
  cfg.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true);
  cfg.getNetworkConfig().getJoin().getTcpIpConfig().addMember("127.0.0.1");
  val instance = Hazelcast.newHazelcastInstance(cfg);
  val hazelcast = instance.getMap(MAP_NAME)

  println(hazelcast.get(("physician:gp:2", SUBJECT, "roles"))) // should give List(gp,physician,...)

}

object LongTest extends App {  

  val cfg = new Config();
  cfg.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
  cfg.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true);
  cfg.getNetworkConfig().getJoin().getTcpIpConfig().addMember(args(0));
  val instance = Hazelcast.newHazelcastInstance(cfg);
  val counter = instance.getAtomicLong("counter")

  val stats = new ThroughputStatistics()
  
  println("Press ENTER to start counting")
  readLine

  var i = 0
  while (true) {
    val c = counter.incrementAndGet()
    i += 1
    stats.tick
    if (i % 1000 == 0) {
      println(s"Counter value = $c")
    }
  }
}