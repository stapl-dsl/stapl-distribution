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
import stapl.core.AbstractPolicy
import stapl.distribution.db.AttributeDatabaseConnectionPool
import stapl.distribution.db.MySQLAttributeDatabaseConnectionPool
import stapl.distribution.components.DistributedCoordinator
import stapl.distribution.components.CoordinatorLocater
import stapl.examples.policies.EhealthPolicy
import stapl.distribution.policies.ConcurrencyPolicies
import stapl.distribution.components.DistributedCoordinatorManager
import stapl.distribution.components.FixedNumberCoordinatorsDistributedCoordinatorManager
import scala.concurrent.Await
import scala.concurrent.duration._
import grizzled.slf4j.Logging
import stapl.distribution.util.StatisticsActor
import stapl.distribution.components.InitialPeakClientForCoordinatorGroup
import stapl.distribution.util.Ehealth29RequestsGenerator
import stapl.distribution.util.EhealthRandomRequestGenerator
import stapl.distribution.util.RandomArtificialRequestGenerator
import stapl.distribution.util.Timer
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.Await
import stapl.core.Decision
import stapl.distribution.components.ClientRegistrationProtocol
import stapl.distribution.components.SimpleDistributedCoordinatorLocater
import stapl.distribution.db.entities.ArtificialEntityManager
import stapl.distribution.db.MockAttributeDatabaseConnectionPool

case class MultipleLocalCoordinatorsPerformanceTestConfig(nbCoordinators: Int = -1, nbWorkersPerCoordinator: Int = -1,
  nbUpdateWorkers: Int = -1, nbRequestsPerCoordinator: Int = -1, databaseIP: String = "not-provided", databasePort: Int = -1,
  logLevel: String = "INFO", enableStatsWorkers: Boolean = false, enableStatsDb: Boolean = false,
  mockDecision: Boolean = false, mockEvaluation: Boolean = false, mockEvaluationDuration: Int = 0)

object MultipleLocalCoordinatorsPerformanceTest extends Logging {

  def main(args: Array[String]) {

    val logLevels = List("OFF", "ERROR", "WARNING", "INFO", "DEBUG")

    val parser = new scopt.OptionParser[MultipleLocalCoordinatorsPerformanceTestConfig]("scopt") {
      head("STAPL - coordinator")

      opt[Int]("nb-coordinators") required () action { (x, c) =>
        c.copy(nbCoordinators = x)
      } text ("The maximal number of coordinators to spawn.")

      opt[Int]("nb-workers-per-coordinator") required () action { (x, c) =>
        c.copy(nbWorkersPerCoordinator = x)
      } text ("The number of workers to spawn per coordinator to evaluate policies asynchronously.")

      opt[Int]("nb-update-workers") required () action { (x, c) =>
        c.copy(nbUpdateWorkers = x)
      } text ("The number of update workers to spawn to process attribute updates asynchronously.")

      opt[Int]("nb-requests-per-coordinator") required () action { (x, c) =>
        c.copy(nbRequestsPerCoordinator = x)
      } text ("The number of requests to send to each coordinator.")

      opt[String]("database-ip") required () action { (x, c) =>
        c.copy(databaseIP = x)
      } text ("The IP address of the machine on which the database containing the attributes is running.")

      opt[Int]("database-port") required () action { (x, c) =>
        c.copy(databasePort = x)
      } text ("The port on which the database containing the attributes is listening.")

      opt[String]("log-level") action { (x, c) =>
        c.copy(logLevel = x)
      } validate { x =>
        if (logLevels.contains(x)) success else failure(s"Invalid log level given. Possible values: $logLevels")
      } text (s"The log level. Valid values: $logLevels")

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
    parser.parse(args, MultipleLocalCoordinatorsPerformanceTestConfig()) map { config =>
      for (nb <- 1 to config.nbCoordinators) {
        info("==================================")
        info(s"Starting test with $nb coordinators")
        val (system, coordinatorLocater) = setupCoordinators(config.copy(nbCoordinators = nb)).get

        implicit val timeout = Timeout(3600 second)
        implicit val ec = system.dispatcher

        // no stats actor, just print out statistics at the end
        val stats = system.actorOf(Props.empty)
        //val requestGenerator = new Ehealth29RequestsGenerator()
        //val requestGenerator = new EhealthRandomRequestGenerator()
        val requestGenerator = new RandomArtificialRequestGenerator(1000, 1000)
        val client = system.actorOf(Props(classOf[InitialPeakClientForCoordinatorGroup], coordinatorLocater, config.nbRequestsPerCoordinator * nb, requestGenerator, stats), "client")
        val f = client ? "go"
        // wait for the "done" back (there should only be one result sent back here)
        Await.result(f, 3600 seconds)

        shutdownActorSystems
        info(s"Finished test with $nb coordinators")
        info("==================================")
      }

    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }

  private def startLocalActorSystem(name: String, port: Int, logLevel: String) = {
    val defaultConf = ConfigFactory.load()
    val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = 127.0.0.1
        akka.remote.netty.tcp.port = $port
        akka.loglevel = $logLevel
      """).withFallback(defaultConf)
    ActorSystem(name, customConf)
  }

  val actorSystems = scala.collection.mutable.ListBuffer[ActorSystem]()

  def setupCoordinators(config: MultipleLocalCoordinatorsPerformanceTestConfig): Option[(ActorSystem, CoordinatorLocater)] = {
    // set up the database
    // Use mock databases in case we are mocking the evaluation or the decision,
    // this allows us to test larger numbers of workers since the database does
    // not limit the number of connections any more
    val pool: AttributeDatabaseConnectionPool = (config.mockDecision | config.mockEvaluation) match {
      case true => new MockAttributeDatabaseConnectionPool
      case false => new MySQLAttributeDatabaseConnectionPool(config.databaseIP, config.databasePort, "stapl-attributes", "root", "root")
    }

    val clientSystem = startLocalActorSystem("STAPL-coordinator", 2552, config.logLevel)
    actorSystems += clientSystem

    // set up the coordinator manager in the client system to which all the
    // coordinators will register
    val coordinatorManager = clientSystem.actorOf(Props(classOf[FixedNumberCoordinatorsDistributedCoordinatorManager], config.nbCoordinators), "distributed-coordinator-manager")

    // set up the coordinators
    val coordinatorLocations = (1 to config.nbCoordinators).map(id => ("127.0.0.1", 2553 + id))
    for (id <- 1 to config.nbCoordinators) {
      val system = startLocalActorSystem("STAPL-coordinator", 2553 + id, config.logLevel)
      actorSystems += system

      // set up the coordinator
      /*DistributedCoordinator(policy: AbstractPolicy, nbWorkers: Int, nbUpdateWorkers: Int,
		  pool: AttributeDatabaseConnectionPool, coordinatorManager: ActorRef,
		  enableStatsIn: Boolean = false, enableStatsOut: Boolean = false, statsOutInterval: Int = 2000,
		  enableStatsWorkers: Boolean = false, enableStatsDb: Boolean = false,
		  mockDecision: Boolean = false, mockEvaluation: Boolean = false,
		  mockEvaluationDuration: Int = 0)*/
      val coordinator = system.actorOf(Props(classOf[DistributedCoordinator], EhealthPolicy.naturalPolicy,
        config.nbWorkersPerCoordinator, config.nbUpdateWorkers, pool, coordinatorManager,
        false, false, -1, config.enableStatsWorkers,
        config.enableStatsDb, config.mockDecision, config.mockEvaluation, config.mockEvaluationDuration), "coordinator")
    }

    // the implicits for later on
    implicit val dispatcher = clientSystem.dispatcher
    implicit val timeout = Timeout(5.second)

    // set up the coordinator locator
    val coordinatorLocater = new SimpleDistributedCoordinatorLocater
    Await.result(coordinatorManager ? ClientRegistrationProtocol.GetListOfCoordinators, 5.seconds) match {
      case ClientRegistrationProtocol.ListOfCoordinators(coordinators) =>
        // we will only receive this message when all coordinators have registered
        coordinatorLocater.setCoordinators(coordinators.map(_._2))
        if (coordinators.size == 0) {
          error("Received the list of coordinators, but no coordinators found. Shutting down")
          shutdownActorSystems
          return None
        }
        info(s"Successfully received the list of coordinators: $coordinators")
      case x =>
        error(s"Failed to get the list of coordinators, shutting down. Received result: $x")
        shutdownActorSystems
        return None
    }

    // some logging
    var mockString = "";
    if (config.mockDecision) {
      mockString = ", mocking decision"
    } else if (config.mockEvaluation) {
      mockString = f", mocking evaluation with duration = ${config.mockEvaluationDuration}ms"
    }
    println(s"Set up ${config.nbCoordinators} local DistributedCoordinators with each ${config.nbWorkersPerCoordinator} workers and ${config.nbUpdateWorkers} update workers (log-level: ${config.logLevel}$mockString)")

    Some(clientSystem, coordinatorLocater)
  }

  def shutdownActorSystems = {
    actorSystems.foreach {
      case x =>
        x.shutdown
        x.awaitTermination(2.seconds) // to make it synchronous
    }
    actorSystems.clear
  }
}