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
import com.hazelcast.config.MapConfig
import com.hazelcast.config.MapStoreConfig
import stapl.distribution.db.AttributeDatabaseConnectionPool
import stapl.distribution.db.HazelcastAttributeDatabaseConnectionPool
import stapl.distribution.db.MySQLAttributeDatabaseConnectionPool

case class ForemanConfig(name: String = "not-provided",
  hostname: String = "not-provided", port: Int = -1,
  foremanManagerIP: String = "not-provided", foremanManagerPort: Int = -1, foremanManagerPath: String = "not-provided",
  nbWorkers: Int = -1, policy: String = "not-provided", databaseIP: String = "not-provided",
  databasePort: Int = -1, databaseType: String = "not-provided")

object ForemanApp {
  def main(args: Array[String]) {

    val policies = Map(
      "ehealth" -> EhealthPolicy.naturalPolicy,
      "chinese-wall" -> ConcurrencyPolicies.chineseWall,
      "count" -> ConcurrencyPolicies.maxNbAccess)
      
    val dbTypes = List("hazelcast", "mysql")

    val parser = new scopt.OptionParser[ForemanConfig]("scopt") {
      head("STAPL - coordinator")
      
      opt[String]("name") required () action { (x, c) =>
        c.copy(name = x)
      } text ("The name of this foreman. This is used for debugging.")
      
      opt[String]("hostname") required () action { (x, c) =>
        c.copy(hostname = x)
      } text ("The hostname of the machine on which this foreman is run. This hostname will be used by other actors in their callbacks, so it should be externally accessible if you deploy the components on different machines.")
      
      opt[Int]("port") required () action { (x, c) =>
        c.copy(port = x)
      } text ("The port on which this foreman will be listening. 0 for a random port")
      
      opt[String]("foreman-manager-ip") required () action { (x, c) =>
        c.copy(foremanManagerIP = x)
      } text ("The IP address of the machine on which the foreman manager is running.")
      
      opt[Int]("foreman-manager-port") required () action { (x, c) =>
        c.copy(foremanManagerPort = x)
      } text ("The port on which the foreman manager is listening.")
      
      opt[String]("foreman-manager-path") required () action { (x, c) =>
        c.copy(foremanManagerPath = x)
      } text ("The Akka actor path of the foreman manager after /user/")
      
      opt[String]("database-ip") required () action { (x, c) =>
        c.copy(databaseIP = x)
      } text ("The IP address of the machine on which the database containing the attributes is running.")
      
      opt[Int]("database-port") required () action { (x, c) =>
        c.copy(databasePort = x)
      } text ("The port on which the database containing the attributes is listening.")
      
      opt[Int]("nb-workers") required () action { (x, c) =>
        c.copy(nbWorkers = x)
      } text ("The number of workers to spawn for this foreman.")
      
      opt[String]("policy") required () action { (x, c) =>
        c.copy(policy = x)
      } validate { x =>
        if (policies.contains(x)) success else failure(s"Invalid policy given. Possible values: ${policies.keys}")
      } text (s"The policy to load in the PDPs. Valid values: ${policies.keys}")
      
      opt[String]("db-type") required () action { (x, c) =>
        c.copy(databaseType = x)
      } validate { x =>
        if (dbTypes.contains(x)) success else failure(s"Invalid database type given. Possible values: $dbTypes")
      } text (s"The type of database to employ. Valid values: $dbTypes")
      
      help("help") text ("prints this usage text")
    }
    // parser.parse returns Option[C]
    parser.parse(args, ForemanConfig()) map { config =>
      val defaultConf = ConfigFactory.load()
      val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = ${config.hostname}
        akka.remote.netty.tcp.port = ${config.port}
      """).withFallback(defaultConf)
      val system = ActorSystem("Foreman", customConf)

      val db: AttributeDatabaseConnectionPool = config.databaseType match {
        case "hazelcast" => 
          val cfg = new Config()
          cfg.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false)
          cfg.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true).addMember(config.foremanManagerIP)
          val mapCfg = new MapConfig("stapl-attributes")
          mapCfg.setMapStoreConfig(new MapStoreConfig()
            .setEnabled(true)
            .setImplementation(new AttributeMapStore(config.databaseIP, config.databasePort, "stapl-attributes", "root", "root")))
          cfg.addMapConfig(mapCfg)
          val hazelcast = Hazelcast.newHazelcastInstance(cfg)
          new HazelcastAttributeDatabaseConnectionPool(hazelcast)
        case "mysql" => new MySQLAttributeDatabaseConnectionPool(config.databaseIP, config.databasePort, "stapl-attributes", "root", "root")          
      }

      val selection =
        system.actorSelection(s"akka.tcp://STAPL-coordinator@${config.foremanManagerIP}:${config.foremanManagerPort}/user/${config.foremanManagerPath}")
      implicit val dispatcher = system.dispatcher
      selection.resolveOne(3.seconds).onComplete {
        case Success(coordinator) =>
          val foreman = system.actorOf(Props(classOf[Foreman], coordinator, config.nbWorkers, policies(config.policy), db), "foreman")
          println(s"Forman ${config.name} up and running at ${config.hostname}:${config.port} with ${config.nbWorkers} workers")
        case Failure(t) =>
          t.printStackTrace()
          system.shutdown
      }
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}