package stapl.distribution

import akka.actor.ActorRef
import akka.actor.ActorLogging
import akka.actor.Actor
import akka.actor.Terminated
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import akka.actor.Props
import scala.collection.mutable.{ Map, Queue }
import stapl.distribution.components.Coordinator
import com.hazelcast.config.Config
import com.hazelcast.config.MapConfig
import com.hazelcast.config.MapStoreConfig
import stapl.distribution.db.AttributeMapStore
import com.hazelcast.core.Hazelcast
import stapl.distribution.db.AttributeDatabaseConnectionPool
import stapl.distribution.db.HazelcastAttributeDatabaseConnectionPool
import stapl.distribution.db.MySQLAttributeDatabaseConnectionPool
import stapl.distribution.components.ConcurrentCoordinatorManager
import stapl.distribution.components.ConcurrentCoordinator

case class ConcurrentCoordinatorConfig(hostname: String = "not-provided", ip: String = "not-provided", port: Int = -1, nbUpdateWorkers: Int = 5,
  databaseIP: String = "not-provided", databasePort: Int = -1, otherCoordinatorIP: String = "not-provided")

object ConcurrentCoordinatorApp {

  def main(args: Array[String]) {

    val parser = new scopt.OptionParser[ConcurrentCoordinatorConfig]("scopt") {
      head("STAPL - coordinator")

      opt[String]('h', "hostname") required () action { (x, c) =>
        c.copy(hostname = x)
      } text ("The hostname of the machine on which this coordinator is run. This hostname will be used by other actors in their callbacks, so it should be externally accessible if you deploy the components on different machines.")

      opt[String]("ip") required () action { (x, c) =>
        c.copy(ip = x)
      } text ("The public IP address of the machine on which this coordinator is run (for accepting external Hazelcast connections).")

      opt[Int]('p', "port") required () action { (x, c) =>
        c.copy(port = x)
      } text ("The port on which this coordinator will be listening.")
      help("help") text ("prints this usage text")

      opt[Int]("nbWorkers") required () action { (x, c) =>
        c.copy(nbUpdateWorkers = x)
      } text ("The number of update workers to spawn to process attribute updates asynchronously.")

      opt[String]("database-ip") required () action { (x, c) =>
        c.copy(databaseIP = x)
      } text ("The IP address of the machine on which the database containing the attributes is running.")

      opt[Int]("database-port") required () action { (x, c) =>
        c.copy(databasePort = x)
      } text ("The port on which the database containing the attributes is listening.")

      opt[String]("other-coordinator-ip") action { (x, c) =>
        c.copy(otherCoordinatorIP = x)
      } text ("The IP address of another coordinator in the cluster. If none given, this means that this coordinator is the first one and will not attempt to join an existing cluster.")

      help("help") text ("prints this usage text")
    }
    // parser.parse returns Option[C]
    parser.parse(args, ConcurrentCoordinatorConfig()) map { config =>
      // set up the Actor system
      val defaultConf = ConfigFactory.load()
      val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = ${config.ip}
        akka.remote.netty.tcp.port = ${config.port}
      """).withFallback(defaultConf)
      val system = ActorSystem("STAPL-coordinator", customConf)

      // set up hazelcast
      val cfg = new Config()
      cfg.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false)
      cfg.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true).addMember(config.ip)
      val mapCfg = new MapConfig("stapl-attributes")
      mapCfg.setMapStoreConfig(new MapStoreConfig()
        .setEnabled(true)
        .setImplementation(new AttributeMapStore(config.databaseIP, config.databasePort, "stapl-attributes", "root", "root")))
      cfg.addMapConfig(mapCfg)
      val hazelcast = Hazelcast.newHazelcastInstance(cfg)
      // set up the database
      val pool = new HazelcastAttributeDatabaseConnectionPool(hazelcast)
      // set up the coordinator manager
      val coordinatorManager = new ConcurrentCoordinatorManager(hazelcast, system)
      // get the id of our coordinator
      val coordinatorId = hazelcast.getAtomicLong("stapl-coordinators").incrementAndGet()

      // set up the coordinator
      val coordinator = system.actorOf(Props(classOf[ConcurrentCoordinator], coordinatorId, pool, config.nbUpdateWorkers, coordinatorManager), "coordinator")
      
      // register the coordinator
      coordinatorManager.register(config.ip,config.port)

      println(s"ConcurrentCoordinator #$coordinatorId up and running at ${config.hostname}:${config.port} with ${config.nbUpdateWorkers} update workers")
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}