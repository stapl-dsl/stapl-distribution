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
import stapl.distribution.components.ClientCoordinatorProtocol
import stapl.distribution.components.SimpleDistributedCoordinatorLocater
import stapl.distribution.components.ClientRegistrationProtocol
import stapl.distribution.components.DistributedCoordinatorConfigurationProtocol
import stapl.distribution.util.LatencyStatisticsActor
import stapl.distribution.components.ClientCoordinatorProtocol._
import stapl.distribution.util.EvaluationEnded
import akka.actor.Cancellable
import stapl.distribution.util.ShutdownAndYouShouldHaveReceived

case class RequestsPerSecondClientForDistributedCoordinatorsConfig(
  ip: String = "not-provided",
  nbWarmups: Int = -1, nbRequestsPerSecond: Int = -1, nbSeconds: Int = -1,
  coordinatorManagerIP: String = "not-provided", coordinatorManagerPort: Int = -1,
  requestPool: String = "ehealth", nbArtificialSubjects: Int = -1, nbArtificialResources: Int = -1,
  logLevel: String = "OFF", waitForGo: Boolean = false,
  nbCoordinators: Int = -1)

object RequestsPerSecondClientForDistributedCoordinatorsApp {
  def main(args: Array[String]) {

    val logLevels = List("OFF", "ERROR", "WARNING", "INFO", "DEBUG")

    val requests = List("ehealth", "artificial")

    val ehealthEM = stapl.distribution.db.entities.ehealth.EhealthEntityManager()
    val concEM = stapl.distribution.db.entities.concurrency.ConcurrencyEntityManager()

    val parser = new scopt.OptionParser[RequestsPerSecondClientForDistributedCoordinatorsConfig]("scopt") {
      head("STAPL - coordinator")

      opt[String]("ip") required () action { (x, c) =>
        c.copy(ip = x)
      } text ("The IP address of the machine on which this client is run. This address will be used by other actors in their callbacks, so it should be externally accessible if you deploy the components on different machines.")

      opt[String]("coordinator-manager-ip") action { (x, c) =>
        c.copy(coordinatorManagerIP = x)
      } text ("The IP address of the coordinator manager in the cluster.")

      opt[Int]("coordinator-manager-port") action { (x, c) =>
        c.copy(coordinatorManagerPort = x)
      } text ("The port of the coordinator manager in the cluster.")

      opt[Int]("nb-warmup-requests") required () action { (x, c) =>
        c.copy(nbWarmups = x)
      } text ("The number of sequential warmup requests that each client should send.")

      opt[Int]("nb-requests-per-second") required () action { (x, c) =>
        c.copy(nbRequestsPerSecond = x)
      } text ("The number of requests per second to send to the coordinators. FOR BEST RESULTS, MAKE THIS A CLEAN NUMBER COMPARED TO 1000, such as 100, 200, 500, 1000, 2000 etc.")

      opt[Int]("nb-seconds") required () action { (x, c) =>
        c.copy(nbSeconds = x)
      } text ("The number of seconds to continue the test.")

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

      help("help") text ("prints this usage text")
    }
    // parser.parse returns Option[C]
    parser.parse(args, RequestsPerSecondClientForDistributedCoordinatorsConfig()) map { config =>
      import DistributedCoordinatorConfigurationProtocol._

      val defaultConf = ConfigFactory.load()
      val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = ${config.ip}
        akka.remote.netty.tcp.port = 0
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
      val stats = system.actorOf(Props(classOf[LatencyStatisticsActor], "RequestsPerSecond client", -1))

      // NOW TRY TO SCHEDULE THE REQUIRED NUMBER OF REQUESTS PER SECOND AS EVENLY AS POSSIBLE
      // DURING EACH SECOND AND KEEP IT UP FOR *AT LEAST* THE REQUESTED NUMBER OF SECONDS
      // Strategy: send messages at most once every 100 ms, but send more than 1 if needed
      val nbRequestsPerInterval = config.nbRequestsPerSecond / 10

      println(s"Sending ${config.nbWarmups} warmup requests followed by ${config.nbRequestsPerSecond} requests per second for ${config.nbSeconds} seconds to a group of ${coordinatorLocater.coordinators.size} coordinators (log-level: ${config.logLevel})")
      if (config.waitForGo) {
        println("Press any key to start generating load")
        Console.readLine()
      }

      // warmup
      for (i <- 1 to config.nbWarmups) {
        val request = em.randomRequest
        val coordinator = coordinatorLocater.getCoordinatorFor(request)
        val f = coordinator ? request
        Await.result(f, 180 seconds) match {
          case x => // nothing to do
        }
      }
      
      var nbSent = 0

      // actual requests
      val cancellable = system.scheduler.schedule(0 millisecond, 100 milliseconds) {
        for (i <- 1 to nbRequestsPerInterval) {
          val request = em.randomRequest
          val coordinator = coordinatorLocater.getCoordinatorFor(request)
          val sentAt = System.nanoTime()
          val f = coordinator ? request
          f onSuccess {
            case AuthorizationDecision(id, decision) =>
              val now = System.nanoTime()
              stats ! EvaluationEnded((now - sentAt).toDouble / 1000000.0)
            case x =>
              throw new RuntimeException(s"No idea what I received here: $x")
          }
          nbSent += 1
        }
      }

      //system.scheduler.scheduleOnce(1 seconds) {
      system.scheduler.scheduleOnce((config.nbSeconds + 1) seconds) {
        cancellable.cancel
        stats ! ShutdownAndYouShouldHaveReceived(nbSent)
      }
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}