package stapl.distribution

import akka.actor.ActorLogging
import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.Props
import scala.concurrent.duration._
import akka.actor.ActorRef
import com.typesafe.config.ConfigFactory
import scala.util.{ Success, Failure }
import scala.concurrent.Future
import scala.concurrent.blocking
import scala.concurrent.ExecutionContext.Implicits.global
import akka.util.Timeout
import scala.concurrent.Await
import stapl.core.Decision
import stapl.core.Deny
import akka.pattern.AskTimeoutException
import akka.routing.BroadcastPool
import akka.actor.actorRef2Scala
import stapl.distribution.util.Timer
import akka.pattern.ask
import stapl.distribution.components.TestClientForCoordinatorGroup
import stapl.distribution.components.HazelcastRemoteDistributedCoordinatorGroup
import stapl.distribution.util.StatisticsActor
import com.hazelcast.core.Hazelcast
import com.hazelcast.config.Config
import org.slf4j.LoggerFactory
import grizzled.slf4j.Logging

case class TestClientForDistributedCoordinatorsConfig(name: String = "not-provided",
  hostname: String = "not-provided", port: Int = -1,
  coordinatorIP: String = "not-provided",
  logLevel: String = "INFO")

object TestClientForDistributedCoordinatorsApp extends Logging {
  def main(args: Array[String]) {

    val logLevels = List("OFF", "ERROR", "WARNING", "INFO", "DEBUG")

    val parser = new scopt.OptionParser[TestClientForDistributedCoordinatorsConfig]("scopt") {
      head("STAPL - Continuous Overload Client")

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

      opt[String]("log-level") action { (x, c) =>
        c.copy(logLevel = x)
      } validate { x =>
        if (logLevels.contains(x)) success else failure(s"Invalid log level given. Possible values: $logLevels")
      } text (s"The log level. Valid values: $logLevels")

      help("help") text ("prints this usage text")
    }
    // parser.parse returns Option[C]
    parser.parse(args, TestClientForDistributedCoordinatorsConfig()) map { config =>      
      val defaultConf = ConfigFactory.load()
      val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = ${config.hostname}
        akka.remote.netty.tcp.port = ${config.port}
        akka.loglevel = ${config.logLevel}
      """).withFallback(defaultConf)
      val system = ActorSystem("STAPL-client", customConf)

      // set log level
      LoggerFactory.getLogger("stapl.distribution").asInstanceOf[ch.qos.logback.classic.Logger]
        .setLevel(ch.qos.logback.classic.Level.valueOf(config.logLevel))

      // set up hazelcast
      val cfg = new Config()
      cfg.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false)
      cfg.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true).addMember(config.coordinatorIP)
      val hazelcast = Hazelcast.newHazelcastInstance(cfg)

      val coordinators = new HazelcastRemoteDistributedCoordinatorGroup(hazelcast, system)
      // tactic: run two peak clients in parallel that each handle half of the peaks
      // Start these clients with a time difference in order to guarantee that the 
      // coordinator is continuously overloaded
      val client1 = system.actorOf(Props(classOf[TestClientForCoordinatorGroup], coordinators), "client1")

      if (coordinators.coordinators.size == 0) {
        println("No coordinators found, shutting down")
        hazelcast.shutdown()
        system.shutdown
        return
      }

      info(s"Test client started at ${config.hostname}:${config.port} for a group of ${coordinators.coordinators.size} coordinators (log-level: ${config.logLevel})")
      client1 ! "go"
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}