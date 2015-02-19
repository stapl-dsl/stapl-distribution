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
import stapl.distribution.components.InitialPeakClientForCoordinatorGroup
import stapl.distribution.components.RemoteDistributedCoordinatorGroup
import stapl.distribution.util.StatisticsActor
import com.hazelcast.core.Hazelcast
import com.hazelcast.config.Config

case class InitialPeakClientForDistributedCoordinatorConfig(name: String = "not-provided",
  hostname: String = "not-provided", port: Int = -1, coordinatorIP: String = "not-provided",
  nbRequests: Int = -1, logLevel: String = "INFO", waitForGo: Boolean = false)

object InitialPeakClientForDistributedCoordinatorsApp {
  def main(args: Array[String]) {

    val logLevels = List("OFF", "ERROR", "WARNING", "INFO", "DEBUG")

    val parser = new scopt.OptionParser[InitialPeakClientForDistributedCoordinatorConfig]("scopt") {
      head("STAPL - coordinator")

      opt[String]("name") required () action { (x, c) =>
        c.copy(name = x)
      } text ("The name of this foreman. This is used for debugging.")

      opt[String]("hostname") required () action { (x, c) =>
        c.copy(hostname = x)
      } text ("The hostname of the machine on which this client is run. This hostname will be used by other actors in their callbacks, so it should be externally accessible if you deploy the components on different machines.")

      opt[Int]("port") required () action { (x, c) =>
        c.copy(port = x)
      } text ("The port on which this client will be listening. 0 for a random port")

      opt[String]("coordinator-ip") required () action { (x, c) =>
        c.copy(coordinatorIP = x)
      } text ("The ip address of the machine on which one of the distributed coordinators is running.")

      opt[Int]("nb-requests") required () action { (x, c) =>
        c.copy(nbRequests = x)
      } text ("The number of requests to send to the coordinator.")

      opt[String]("log-level") action { (x, c) =>
        c.copy(logLevel = x)
      } validate { x =>
        if (logLevels.contains(x)) success else failure(s"Invalid log level given. Possible values: $logLevels")
      } text (s"The log level. Valid values: $logLevels")

      opt[Unit]("wait-for-go") action { (x, c) =>
        c.copy(waitForGo = true)
      } text ("Flag to indicate that the client should wait for user input to start generating load.")

      help("help") text ("prints this usage text")
    }
    // parser.parse returns Option[C]
    parser.parse(args, InitialPeakClientForDistributedCoordinatorConfig()) map { config =>
      val defaultConf = ConfigFactory.load()
      val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = ${config.hostname}
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
      val stats = system.actorOf(Props(classOf[StatisticsActor], "Continuous overload clients", 2000, 10))
      
      val client = system.actorOf(Props(classOf[InitialPeakClientForCoordinatorGroup], coordinators, config.nbRequests, stats), "client")
      println(s"InitialPeak client started at ${config.hostname}:${config.port} doint ${config.nbRequests} requests to a group of ${coordinators.coordinators.size} coordinators (log-level: ${config.logLevel})")
      if(config.waitForGo) {
        println("Press any key to start generating load")
        Console.readLine()
      }
      client ! "go"
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}