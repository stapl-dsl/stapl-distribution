/**
 *    Copyright 2015 KU Leuven Research and Developement - iMinds - Distrinet
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    Administrative Contact: dnet-project-office@cs.kuleuven.be
 *    Technical Contact: maarten.decat@cs.kuleuven.be
 *    Author: maarten.decat@cs.kuleuven.be
 */
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
import stapl.distribution.components.HazelcastDistributedCoordinatorLocater
import stapl.distribution.components.ClientCoordinatorProtocol.AuthorizationRequest
import stapl.distribution.util.ThroughputAndLatencyStatisticsActor
import com.hazelcast.config.Config
import com.hazelcast.config.MapConfig
import com.hazelcast.config.MapStoreConfig
import com.hazelcast.core.Hazelcast
import stapl.distribution.components.SequentialClientForCoordinatorGroup
import stapl.distribution.db.entities.ehealth.EhealthEntityManager
import stapl.distribution.db.entities.ArtificialEntityManager
import org.slf4j.LoggerFactory
import grizzled.slf4j.Logging
import stapl.distribution.components.SimpleDistributedCoordinatorLocater
import stapl.distribution.components.ClientRegistrationProtocol
import stapl.distribution.components.DistributedCoordinatorConfigurationProtocol
import stapl.distribution.util.LatencyStatisticsActor

case class SequentialClientForDistributedCoordinatorsConfig(
  ip: String = "not-provided", port: Int = -1,
  nbThreads: Int = -1, nbWarmups: Int = -1, nbRequests: Int = -1, policy: String = "not-provided",
  coordinatorManagerIP: String = "not-provided", coordinatorManagerPort: Int = -1,
  requestPool: String = "ehealth", nbArtificialSubjects: Int = -1, nbArtificialResources: Int = -1,
  statsInterval: Int = 2000,
  logLevel: String = "OFF", waitForGo: Boolean = false,
  nbCoordinators: Int = -1)

object SequentialClientForDistributedCoordinatorsApp {
  def main(args: Array[String]) {

    val logLevels = List("OFF", "ERROR", "WARNING", "INFO", "DEBUG")

    val requests = List("ehealth", "artificial")

    val ehealthEM = stapl.distribution.db.entities.ehealth.EhealthEntityManager()
    val concEM = stapl.distribution.db.entities.concurrency.ConcurrencyEntityManager()

    val parser = new scopt.OptionParser[SequentialClientForDistributedCoordinatorsConfig]("scopt") {
      head("STAPL - coordinator")

      opt[String]("ip") required () action { (x, c) =>
        c.copy(ip = x)
      } text ("The hostname of the machine on which this client is run. This hostname will be used by other actors in their callbacks, so it should be externally accessible if you deploy the components on different machines.")

      opt[Int]("port") required () action { (x, c) =>
        c.copy(port = x)
      } text ("The port on which this client will be listening. 0 for a random port")

      opt[String]("coordinator-manager-ip") action { (x, c) =>
        c.copy(coordinatorManagerIP = x)
      } text ("The IP address of the coordinator manager in the cluster.")

      opt[Int]("coordinator-manager-port") action { (x, c) =>
        c.copy(coordinatorManagerPort = x)
      } text ("The port of the coordinator manager in the cluster.")

      opt[Int]("nb-threads") required () action { (x, c) =>
        c.copy(nbThreads = x)
      } text ("The number of parallel client threads to start.")

      opt[Int]("nb-warmup-runs") required () action { (x, c) =>
        c.copy(nbWarmups = x)
      } text ("The number of sequential warmup requests that each client should send.")

      opt[Int]("nb-runs") required () action { (x, c) =>
        c.copy(nbRequests = x)
      } text ("The number of sequential requests that each client should send. 0 for infinite.")

      opt[Int]("nb-coordinators") required () action { (x, c) =>
        c.copy(nbCoordinators = x)
      } text ("The number of coordinators that should be active.")

      //      opt[String]("request-pool") action { (x, c) =>
      //        c.copy(requestPool = x)
      //      } validate { x =>
      //        if (requests.contains(x)) success else failure(s"Invalid value given for --requests. Possible values: $requests")
      //      } text (s"The type of requests to send out. Possible values: ehealth = send out random requests from the ehealth entities, " + 
      //          "artificial = send out random requests from a set of artificial entities of chosen size")
      //
      //      opt[Int]("nb-artificial-subjects") action { (x, c) =>
      //        c.copy(nbArtificialSubjects = x)
      //      } text ("The number of artificial subjects to be used for generating the random requests. Default: 1000. Only used when --request-pool == artificial.")
      //
      //      opt[Int]("nb-artificial-resources") action { (x, c) =>
      //        c.copy(nbArtificialResources = x)
      //      } text ("The number of artificial resources to be used for generating the random requests. Default: 1000. Only used when --request-pool == artificial.")

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
      import DistributedCoordinatorConfigurationProtocol._

      val defaultConf = ConfigFactory.load()
      val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = ${config.ip}
        akka.remote.netty.tcp.port = ${config.port}
        akka.loglevel = ${config.logLevel}
      """).withFallback(defaultConf)
      val system = ActorSystem("STAPL-client", customConf)

      // set log level
      LoggerFactory.getLogger("stapl.distribution").asInstanceOf[ch.qos.logback.classic.Logger]
        .setLevel(ch.qos.logback.classic.Level.valueOf(config.logLevel))

      val coordinatorLocater = new SimpleDistributedCoordinatorLocater
      val selection = system.actorSelection(s"akka.tcp://STAPL-coordinator@${config.coordinatorManagerIP}:${config.coordinatorManagerPort}/user/distributed-coordinator-manager")
      implicit val dispatcher = system.dispatcher
      implicit val timeout = Timeout(5.second)
      val coordinatorManager = Await.result(selection.resolveOne(3.seconds), 5.seconds)
      // reconfigure the manager
      Await.result(coordinatorManager ? SetNumberCoordinators(config.nbCoordinators), 180 seconds) match {
        case SetNumberCoordinatorsSuccess => // nothing to do
        case x =>
          throw new RuntimeException(s"WTF did I receive: $x")
      }
      Thread.sleep(100) // to avoid a race condition with reconfiguring the coordinators themselves
      // set up the tests
      Await.result(coordinatorManager ? ClientRegistrationProtocol.GetListOfCoordinators, 5.seconds) match {
        case ClientRegistrationProtocol.ListOfCoordinators(coordinators) =>
          coordinatorLocater.setCoordinators(coordinators.map(_._2))
          if (coordinators.size == 0) {
            println("Received the list of coordinators, but no coordinators found. Shutting down")
            system.shutdown
            return
          }
          println(s"Successfully received the list of coordinators: $coordinators")
        case x =>
          println(s"Failed to get the list of coordinators, shutting down. Received result: $x")
          system.shutdown
          return
      }

      val em = EhealthEntityManager(true)
      //      val em = config.requestPool match {
      //        case "ehealth" => EhealthEntityManager(true)
      //        case "artificial" => ArtificialEntityManager(config.nbArtificialSubjects, config.nbArtificialResources)
      //      }
      //val stats = system.actorOf(Props(classOf[ThroughputAndLatencyStatisticsActor], "Sequential clients", 0 /* no periodical output */, 10, false))
      val stats = system.actorOf(Props(classOf[LatencyStatisticsActor], "Sequential clients", config.nbThreads * config.nbRequests))
      val clients = system.actorOf(BroadcastPool(config.nbThreads).props(Props(classOf[SequentialClientForCoordinatorGroup], coordinatorLocater, em, stats)), "clients")

      println(s"Started ${config.nbThreads} sequential client threads that each will make ${config.nbWarmups} warmup requests and ${config.nbRequests} requests to a group of ${coordinatorLocater.coordinators.size} coordinators (log-level: ${config.logLevel})")

      if (config.waitForGo) {
        println("Press any key to start generating load")
        Console.readLine()
      }
      import components.ClientProtocol._
      clients ! (config.nbWarmups, config.nbRequests)
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}