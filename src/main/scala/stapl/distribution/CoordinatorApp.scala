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

case class CoordinatorConfig(hostname: String = "not-provided", ip: String = "not-provided", port: Int = -1, nbUpdateWorkers: Int = 5,
  databaseIP: String = "not-provided", databasePort: Int = -1, databaseType: String = "not-provided")

object CoordinatorApp {

  def main(args: Array[String]) {
      
    val dbTypes = List("hazelcast", "mysql")
    
    val parser = new scopt.OptionParser[CoordinatorConfig]("scopt") {
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
      
      opt[String]("db-type") required () action { (x, c) =>
        c.copy(databaseType = x)
      } validate { x =>
        if (dbTypes.contains(x)) success else failure(s"Invalid database type given. Possible values: $dbTypes")
      } text (s"The type of database to employ. Valid values: $dbTypes")

      help("help") text ("prints this usage text")
    }
    // parser.parse returns Option[C]
    parser.parse(args, CoordinatorConfig()) map { config =>
      // set up the Actor system
      val defaultConf = ConfigFactory.load()
      val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = ${config.ip}
        akka.remote.netty.tcp.port = ${config.port}
      """).withFallback(defaultConf)
      val system = ActorSystem("STAPL-coordinator", customConf)

      val db: AttributeDatabaseConnectionPool = config.databaseType match {
        case "hazelcast" => new HazelcastAttributeDatabaseConnectionPool(config.ip, config.databaseIP, config.databasePort)
        case "mysql" => new MySQLAttributeDatabaseConnectionPool(config.databaseIP, config.databasePort, "stapl-attributes", "root", "root")          
      }

      val coordinator = system.actorOf(Props(classOf[Coordinator], db, config.nbUpdateWorkers), "coordinator")

      println(s"Coordinator up and running at ${config.hostname}:${config.port} with ${config.nbUpdateWorkers} update workers")
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}