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
import stapl.distribution.components.ContinuousOverloadClientForCoordinatorGroup
import stapl.distribution.components.RemoteDistributedCoordinatorGroup
import stapl.distribution.util.StatisticsActor
import com.hazelcast.core.Hazelcast
import com.hazelcast.config.Config
import stapl.distribution.db.entities.ehealth.EhealthEntityManager
import stapl.distribution.db.entities.ArtificialEntityManager

case class ContinuousOverloadClientForDistributedCoordinatorConfig(name: String = "not-provided",
  hostname: String = "not-provided", port: Int = -1,
  coordinatorIP: String = "not-provided",
  nbRequests: Int = -1, nbPeaks: Int = -1,
  requestPool: String = "ehealth", nbArtificialSubjects: Int = -1, nbArtificialResources: Int = -1,
  statsInterval: Int = 2000, logLevel: String = "INFO", waitForGo: Boolean = false)

object ContinuousOverloadClientForDistributedCoordinatorsApp {
  def main(args: Array[String]) {
      
    val logLevels = List("OFF", "ERROR", "WARNING", "INFO", "DEBUG")

    val requests = List("ehealth", "artificial")
    
    val parser = new scopt.OptionParser[ContinuousOverloadClientForDistributedCoordinatorConfig]("scopt") {
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
      
      opt[Int]("nb-requests") required () action { (x, c) =>
        c.copy(nbRequests = x)
      } text ("The number of requests to send to the coordinator for each peak.")
      
      opt[Int]("nb-peaks") required () action { (x, c) =>
        c.copy(nbPeaks = x)
      } text ("The number of peaks to perform. 0 for infinity")

      opt[String]("request-pool") action { (x, c) =>
        c.copy(requestPool = x)
      } validate { x =>
        if (requests.contains(x)) success else failure(s"Invalid value given for --requests. Possible values: $requests")
      } text (s"The type of requests to send out. Possible values: ehealth = send out random requests from the ehealth entities, " + 
          "artificial = send out random requests from a set of artificial entities of chosen size")

      opt[Int]("nb-artificial-subjects") action { (x, c) =>
        c.copy(nbArtificialSubjects = x)
      } text ("The number of artificial subjects to be used for generating the random requests. Default: 1000. Only used when --request-pool == artificial.")

      opt[Int]("nb-artificial-resources") action { (x, c) =>
        c.copy(nbArtificialResources = x)
      } text ("The number of artificial resources to be used for generating the random requests. Default: 1000. Only used when --request-pool == artificial.")
      
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
    parser.parse(args, ContinuousOverloadClientForDistributedCoordinatorConfig()) map { config =>
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
      val em = config.requestPool match {
        case "ehealth" => EhealthEntityManager()
        case "artificial" => ArtificialEntityManager(config.nbArtificialSubjects, config.nbArtificialResources)
      }
      val stats = system.actorOf(Props(classOf[StatisticsActor],"Continuous overload clients",config.statsInterval,10))
      // tactic: run two peak clients in parallel that each handle half of the peaks
      // Start these clients with a time difference in order to guarantee that the 
      // coordinator is continuously overloaded
      val client1 = system.actorOf(Props(classOf[ContinuousOverloadClientForCoordinatorGroup], coordinators, em, config.nbRequests, config.nbPeaks / 2, stats), "client1")
      val client2 = system.actorOf(Props(classOf[ContinuousOverloadClientForCoordinatorGroup], coordinators, em, config.nbRequests, config.nbPeaks - (config.nbPeaks / 2), stats), "client2")
      
      if(coordinators.coordinators.size == 0) {
        println("No coordinators found, shutting down")
        hazelcast.shutdown()
        system.shutdown
        return
      }
      
      println(s"Continuous overload client started at ${config.hostname}:${config.port} doing ${config.nbPeaks} peaks of each ${config.nbRequests} ${config.requestPool} requests to a group of ${coordinators.coordinators.size} coordinators (log-level: ${config.logLevel})")
      if(config.waitForGo) {
        println("Press any key to start generating load")
        Console.readLine()
      }
      client1 ! "go"
      Future { 
        // have the other client start after 1 sec
        blocking { Thread.sleep(1000L) }
        client2 ! "go" 
      }
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}