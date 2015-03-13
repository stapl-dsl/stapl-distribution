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
import stapl.distribution.components.ConcurrentCoordinator
import stapl.distribution.components.ConcurrentCoordinatorManager
import stapl.distribution.components.ForemanManager

case class ConcurrentCoordinatorConfig(hostname: String = "not-provided", ip: String = "not-provided", port: Int = -1, nbUpdateWorkers: Int = 5,
  databaseIP: String = "not-provided", databasePort: Int = -1, nbCoordinators: Int = -1, databaseType: String = "not-provided",
  logLevel: String = "INFO")

object ConcurrentCoordinatorApp {

  def main(args: Array[String]) {
      
    val logLevels = List("OFF", "ERROR", "WARNING", "INFO", "DEBUG")
    
    val dbTypes = List("hazelcast", "mysql")

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

      opt[Int]("nb-workers") required () action { (x, c) =>
        c.copy(nbUpdateWorkers = x)
      } text ("The number of update workers per concurrent coordinator to spawn to process attribute updates asynchronously.")

      opt[Int]("nb-coordinators") required () action { (x, c) =>
        c.copy(nbCoordinators = x)
      } text ("The number of concurrent coordinators to spawn.")

      opt[String]("database-ip") required () action { (x, c) =>
        c.copy(databaseIP = x)
      } text ("The IP address of the machine on which the database containing the attributes is running.")

      opt[Int]("database-port") required () action { (x, c) =>
        c.copy(databasePort = x)
      } text ("The port on which the database containing the attributes is listening.")
      
      opt[String]("db-type") required () action { (x, c) =>
        c.copy(databaseType = x)
      } validate { x =>
        if (dbTypes.contains(x)) success else failure(s"Invalid database type given. Possible values: $dbTypes")
      } text (s"The type of database to employ. Valid values: $dbTypes")
      
      opt[String]("log-level") action { (x, c) =>
        c.copy(logLevel = x)
      } validate { x =>
        if (logLevels.contains(x)) success else failure(s"Invalid log level given. Possible values: $logLevels")
      } text (s"The log level. Valid values: $logLevels")

      help("help") text ("prints this usage text")
    }
    // parser.parse returns Option[C]
    parser.parse(args, ConcurrentCoordinatorConfig()) map { config =>
      // set up the Actor system
      val defaultConf = ConfigFactory.load()
      val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = ${config.ip}
        akka.remote.netty.tcp.port = ${config.port}
        akka.loglevel = ${config.logLevel}
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
      val pool: AttributeDatabaseConnectionPool = config.databaseType match {
        case "hazelcast" => new HazelcastAttributeDatabaseConnectionPool(hazelcast)
        case "mysql" => new MySQLAttributeDatabaseConnectionPool(config.databaseIP, config.databasePort, "stapl-attributes", "root", "root")          
      }

      // set up the coordinators
      val coordinatorManager = system.actorOf(Props(classOf[ConcurrentCoordinatorManager], config.nbCoordinators, pool, config.nbUpdateWorkers), "coordinator-manager") 

      println(s"${config.nbCoordinators} concurrent coordinators up and running at ${config.hostname}:${config.port} with each ${config.nbUpdateWorkers} update workers (log-level: ${config.logLevel})")
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}