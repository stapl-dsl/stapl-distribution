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
import stapl.distribution.db.MockAttributeDatabaseConnectionPool
import stapl.distribution.components.DistributedCoordinator
import stapl.distribution.components.HazelcastDistributedCoordinatorLocater
import stapl.examples.policies.EhealthPolicy
import stapl.distribution.policies.ConcurrencyPolicies
import stapl.distribution.components.DistributedCoordinatorManager
import scala.concurrent.Await
import scala.concurrent.duration._

case class DistributedCoordinatorConfig(hostname: String = "not-provided", ip: String = "not-provided", port: Int = -1,
  nbWorkers: Int = -1, nbUpdateWorkers: Int = -1, databaseIP: String = "not-provided", databasePort: Int = -1,
  coordinatorManagerIP: String = "not-provided", coordinatorManagerPort: Int = -1, databaseType: String = "not-provided",
  policy: String = "not-provided",
  logLevel: String = "INFO", enableStatsIn: Boolean = false, enableStatsOut: Boolean = false,
  statsOutInterval: Int = 2000, enableStatsWorkers: Boolean = false, enableStatsDb: Boolean = false,
  mockDecision: Boolean = false, mockEvaluation: Boolean = false, mockEvaluationDuration: Int = 0)

object DistributedCoordinatorApp {

  def main(args: Array[String]) {

    val logLevels = List("OFF", "ERROR", "WARNING", "INFO", "DEBUG")

    val dbTypes = List("hazelcast", "mysql")

    val policies = Map(
      "ehealth" -> EhealthPolicy.naturalPolicy,
      "chinese-wall" -> ConcurrencyPolicies.chineseWall,
      "count" -> ConcurrencyPolicies.maxNbAccess)

    val parser = new scopt.OptionParser[DistributedCoordinatorConfig]("scopt") {
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
        c.copy(nbWorkers = x)
      } text ("The number of workers to spawn to evaluate policies asynchronously.")

      opt[Int]("nb-update-workers") required () action { (x, c) =>
        c.copy(nbUpdateWorkers = x)
      } text ("The number of update workers to spawn to process attribute updates asynchronously.")

      opt[String]("db-type") required () action { (x, c) =>
        c.copy(databaseType = x)
      } validate { x =>
        if (dbTypes.contains(x)) success else failure(s"Invalid database type given. Possible values: $dbTypes")
      } text (s"The type of database to employ. Valid values: $dbTypes")

      opt[String]("database-ip") required () action { (x, c) =>
        c.copy(databaseIP = x)
      } text ("The IP address of the machine on which the database containing the attributes is running.")

      opt[Int]("database-port") required () action { (x, c) =>
        c.copy(databasePort = x)
      } text ("The port on which the database containing the attributes is listening.")

      opt[String]("coordinator-manager-ip") action { (x, c) =>
        c.copy(coordinatorManagerIP = x)
      } text ("The IP address of the coordinator manager in the cluster. If none given, this means that this coordinator is the first one and will not attempt to join an existing cluster.")

      opt[Int]("coordinator-manager-port") action { (x, c) =>
        c.copy(coordinatorManagerPort = x)
      } text ("The port of the coordinator manager in the cluster. Only needed if coordinator-manager-port is given.")

      opt[String]("policy") required () action { (x, c) =>
        c.copy(policy = x)
      } validate { x =>
        if (policies.contains(x)) success else failure(s"Invalid policy given. Possible values: ${policies.keys}")
      } text (s"The policy to load in the PDPs. Valid values: ${policies.keys}")

      opt[String]("log-level") action { (x, c) =>
        c.copy(logLevel = x)
      } validate { x =>
        if (logLevels.contains(x)) success else failure(s"Invalid log level given. Possible values: $logLevels")
      } text (s"The log level. Valid values: $logLevels")

      opt[Unit]("enable-stats-in") action { (x, c) =>
        c.copy(enableStatsIn = true)
      } text (s"Flag to indicate that the coordinator should output stats about the incoming requests.")

      opt[Unit]("enable-stats-out") action { (x, c) =>
        c.copy(enableStatsOut = true)
      } text (s"Flag to indicate that the coordinator should output stats about the outoing decisions.")

      opt[Int]("stats-out-interval") action { (x, c) =>
        c.copy(statsOutInterval = x)
      } text ("The interval on which to report on output stats, in number of requests. " +
        "Only taken into account if --enable-stats-out is set. Default: 2000.")

      opt[Unit]("enable-stats-db") action { (x, c) =>
        c.copy(enableStatsDb = true)
      } text (s"Flag to indicate that the coordinator should output stats about the attribute database.")

      opt[Unit]("enable-stats-workers") action { (x, c) =>
        c.copy(enableStatsWorkers = true)
      } text (s"Flag to indicate that the coordinator should output stats about the workers.")

      opt[Unit]("mock-decision") action { (x, c) =>
        c.copy(mockDecision = true)
      } text (s"Flag to indicate that the coordinator should not pass the work to workers, " +
        "but just return a mock decision to the client immediately.")

      opt[Unit]("mock-evaluation") action { (x, c) =>
        c.copy(mockEvaluation = true)
      } text (s"Flag to indicate that the coordinator should pass the work to workers, " +
        "but that these workers should not evaluate the actual policy and just return a mock decision " +
        "to the coordinator immediately. This option is ignored if --mock-desision is set as well, since " +
        "the request will never reach the workers.")

      opt[Int]("mock-evaluation-duration") action { (x, c) =>
        c.copy(mockEvaluationDuration = x)
      } text ("The duration of a mock evaluation in ms. Default: 0ms. Only used when --mock-evaluation-duration is set.")

      help("help") text ("prints this usage text")
    }
    // parser.parse returns Option[C]
    parser.parse(args, DistributedCoordinatorConfig()) map { config =>
      // set up the Actor system
      val defaultConf = ConfigFactory.load()
      val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = ${config.ip}
        akka.remote.netty.tcp.port = ${config.port}
        akka.loglevel = ${config.logLevel}
      """).withFallback(defaultConf)
      val system = ActorSystem("STAPL-coordinator", customConf)

      // set up the database
      // Use mock databases in case we are mocking the evaluation or the decision,
      // this allows us to test larger numbers of workers since the database does
      // not limit the number of connections any more
      val pool: AttributeDatabaseConnectionPool = (config.mockDecision | config.mockEvaluation) match {
        case true => new MockAttributeDatabaseConnectionPool
        case false => config.databaseType match {
          case "hazelcast" =>
            val cfg = new Config()
            cfg.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false)
            cfg.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true).addMember(config.ip)
            val mapCfg = new MapConfig("stapl-attributes")
            mapCfg.setMapStoreConfig(new MapStoreConfig()
              .setEnabled(true)
              .setImplementation(new AttributeMapStore(config.databaseIP, config.databasePort, "stapl-attributes", "root", "root")))
            cfg.addMapConfig(mapCfg)
            val hazelcast = Hazelcast.newHazelcastInstance(cfg)
            new HazelcastAttributeDatabaseConnectionPool(hazelcast)
          case "mysql" => new MySQLAttributeDatabaseConnectionPool(config.databaseIP, config.databasePort, "stapl-attributes", "root", "root")
        }
      }

      // set up the coordinator manager
      //      val coordinatorManager = new HazelcastDistributedCoordinatorLocater(hazelcast, system)
      // get the id of our coordinator
      //      val coordinatorId = hazelcast.getAtomicLong("stapl-coordinators").incrementAndGet()
      // new method: set up a coordinatorManager if we are the master or connect to the master
      // coordinatorManager otherwise
      val coordinatorManager = config.coordinatorManagerIP match {
        case "not-provided" =>
          // we are the master
          val result = system.actorOf(Props[DistributedCoordinatorManager], "distributed-coordinator-manager")
          println("Set up a new master coordinatorManager")
          result
        case ip =>
          // we should connect to the master coordinatorManager otherwise
          val selection = system.actorSelection(s"akka.tcp://STAPL-coordinator@${config.coordinatorManagerIP}:${config.coordinatorManagerPort}/user/distributed-coordinator-manager")
          implicit val dispatcher = system.dispatcher
          val result = Await.result(selection.resolveOne(3.seconds), 5.seconds)
          println(s"Connected to master coordinatorManager at ${config.coordinatorManagerIP}:${config.coordinatorManagerPort}")
          result
      }

      // set up the coordinator
      val coordinator = system.actorOf(Props(classOf[DistributedCoordinator], policies(config.policy),
        config.nbWorkers, config.nbUpdateWorkers, pool, coordinatorManager,
        config.enableStatsIn, config.enableStatsOut, config.statsOutInterval, config.enableStatsWorkers, config.enableStatsDb,
        config.mockDecision, config.mockEvaluation, config.mockEvaluationDuration), "coordinator")

      var mockString = "";
      if (config.mockDecision) {
        mockString = ", mocking decision"
      } else if (config.mockEvaluation) {
        mockString = f", mocking evaluation with duration = ${config.mockEvaluationDuration}ms"
      }
      println(s"DistributedCoordinator up and running at ${config.hostname}:${config.port} with ${config.nbWorkers} workers and ${config.nbUpdateWorkers} update workers (log-level: ${config.logLevel}$mockString)")
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}