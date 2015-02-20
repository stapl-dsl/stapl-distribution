package stapl.distribution

import akka.actor.ActorLogging
import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.Props
import scala.concurrent.duration._
import akka.actor.ActorRef
import com.typesafe.config.ConfigFactory
import scala.util.{ Success, Failure }
import akka.util.Timeout
import scala.concurrent.Await
import stapl.core.Decision
import stapl.core.Deny
import akka.pattern.AskTimeoutException
import akka.routing.BroadcastPool
import akka.actor.actorRef2Scala
import stapl.distribution.util.Timer
import akka.pattern.ask
import stapl.distribution.components.SequentialClientForConcurrentCoordinators
import stapl.distribution.components.RemoteDistributedCoordinatorGroup
import stapl.distribution.components.ClientCoordinatorProtocol.AuthorizationRequest
import stapl.distribution.util.StatisticsActor
import com.hazelcast.config.Config
import com.hazelcast.config.MapConfig
import com.hazelcast.config.MapStoreConfig
import com.hazelcast.core.Hazelcast
import stapl.distribution.components.SequentialClientForCoordinatorGroup

case class SequentialClientForDistributedCoordinatorsConfig(name: String = "not-provided",
  ip: String = "not-provided", port: Int = -1,
  nbThreads: Int = -1, nbRequests: Int = -1, policy: String = "not-provided",
  coordinatorIP: String = "not-provided",
  statsInterval: Int = 2000,
  logLevel: String = "INFO", waitForGo: Boolean = false)

object SequentialClientForDistributedCoordinatorsApp {
  def main(args: Array[String]) {

    val logLevels = List("OFF", "ERROR", "WARNING", "INFO", "DEBUG")

    val ehealthEM = stapl.distribution.db.entities.ehealth.EntityManager()
    val concEM = stapl.distribution.db.entities.concurrency.EntityManager()

    val parser = new scopt.OptionParser[SequentialClientForDistributedCoordinatorsConfig]("scopt") {
      head("STAPL - coordinator")

      opt[String]("name") required () action { (x, c) =>
        c.copy(name = x)
      } text ("The name of this foreman. This is used for debugging.")

      opt[String]("ip") required () action { (x, c) =>
        c.copy(ip = x)
      } text ("The hostname of the machine on which this client is run. This hostname will be used by other actors in their callbacks, so it should be externally accessible if you deploy the components on different machines.")

      opt[Int]("port") required () action { (x, c) =>
        c.copy(port = x)
      } text ("The port on which this client will be listening. 0 for a random port")

      opt[String]("coordinator-ip") required () action { (x, c) =>
        c.copy(coordinatorIP = x)
      } text ("The IP address of the machine on which one of the coordinators in the group is running.")

      opt[Int]("nb-threads") required () action { (x, c) =>
        c.copy(nbThreads = x)
      } text ("The number of parallel client threads to start.")

      opt[Int]("nb-requests") required () action { (x, c) =>
        c.copy(nbRequests = x)
      } text ("The number of sequential requests that each client should send. 0 for infinite.")

      opt[String]("log-level") action { (x, c) =>
        c.copy(logLevel = x)
      } validate { x =>
        if (logLevels.contains(x)) success else failure(s"Invalid log level given. Possible values: $logLevels")
      } text (s"The log level. Valid values: $logLevels")

      opt[Unit]("wait-for-go") action { (x, c) =>
        c.copy(waitForGo = true)
      } text ("Flag to indicate that the client should wait for user input to start generating load.")

      opt[Int]("stats-interval") action { (x, c) =>
        c.copy(statsInterval = x)
      } text ("The interval on which to report stats, in number of requests. Default: 2000.")

      help("help") text ("prints this usage text")
    }
    // parser.parse returns Option[C]
    parser.parse(args, SequentialClientForDistributedCoordinatorsConfig()) map { config =>
      val defaultConf = ConfigFactory.load()
      val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = ${config.ip}
        akka.remote.netty.tcp.port = ${config.port}
        akka.loglevel = ${config.logLevel}
      """).withFallback(defaultConf)
      val system = ActorSystem("STAPL-client", customConf)

      // set up hazelcast
      val cfg = new Config()
      cfg.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false)
      cfg.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true).addMember(config.coordinatorIP)
      val hazelcast = Hazelcast.newHazelcastInstance(cfg)

      val coordinators = new RemoteDistributedCoordinatorGroup(hazelcast, system)
      val stats = system.actorOf(Props(classOf[StatisticsActor], "Continuous overload clients", config.statsInterval, 10))
      val clients = system.actorOf(BroadcastPool(config.nbThreads).props(Props(classOf[SequentialClientForCoordinatorGroup], coordinators, stats)), "clients")

      if (coordinators.coordinators.size == 0) {
        println("No coordinators found, shutting down")
        hazelcast.shutdown()
        system.shutdown
        return
      }

      println(s"Started ${config.nbThreads} sequential client threads that each will send ${config.nbRequests} requests to a group of ${coordinators.coordinators.size} coordinators (log-level: ${config.logLevel})")

      if (config.waitForGo) {
        println("Press any key to start generating load")
        Console.readLine()
      }
      import components.ClientProtocol._
      clients ! Go(config.nbRequests)
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}