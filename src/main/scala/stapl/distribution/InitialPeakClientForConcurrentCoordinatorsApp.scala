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
import stapl.distribution.components.InitialPeakClientForCoordinatorGroup
import stapl.distribution.components.RemoteConcurrentCoordinatorGroup
import stapl.distribution.util.ThroughputAndLatencyStatisticsActor

case class InitialPeakClientForConcurrentCoordinatorConfig(name: String = "not-provided",
  hostname: String = "not-provided", port: Int = -1,
  coordinatorManagerHostname: String = "not-provided", coordinatorManagerPort: Int = -1,
  nbRequests: Int = -1,
  logLevel: String = "INFO")

object InitialPeakClientForConcurrentCoordinatorsApp {
  def main(args: Array[String]) {
      
    val logLevels = List("OFF", "ERROR", "WARNING", "INFO", "DEBUG")
    
    val parser = new scopt.OptionParser[InitialPeakClientForConcurrentCoordinatorConfig]("scopt") {
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
      
      opt[String]("coordinator-manager-hostname") required () action { (x, c) =>
        c.copy(coordinatorManagerHostname = x)
      } text ("The hostname of the machine on which the concurrent coordinators and their manager are running.")
      
      opt[Int]("coordinator-manager-port") required () action { (x, c) =>
        c.copy(coordinatorManagerPort = x)
      } text ("The port on which the concurrent coordinators and their manager are running.")
      
      opt[Int]("nb-requests") required () action { (x, c) =>
        c.copy(nbRequests = x)
      } text ("The number of requests to send to the coordinator.")
      
      opt[String]("log-level") action { (x, c) =>
        c.copy(logLevel = x)
      } validate { x =>
        if (logLevels.contains(x)) success else failure(s"Invalid log level given. Possible values: $logLevels")
      } text (s"The log level. Valid values: $logLevels")
      
      help("help") text ("prints this usage text")
    }
    // parser.parse returns Option[C]
    parser.parse(args, InitialPeakClientForConcurrentCoordinatorConfig()) map { config =>
      val defaultConf = ConfigFactory.load()
      val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = ${config.hostname}
        akka.remote.netty.tcp.port = ${config.port}
        akka.loglevel = ${config.logLevel}
      """).withFallback(defaultConf)
      val system = ActorSystem("STAPL-client", customConf)

      val coordinators = new RemoteConcurrentCoordinatorGroup(system, config.coordinatorManagerHostname, config.coordinatorManagerPort)
      val stats = system.actorOf(Props(classOf[ThroughputAndLatencyStatisticsActor],"Initial peak clients",1000,10))
      val client = system.actorOf(Props(classOf[InitialPeakClientForCoordinatorGroup], coordinators, config.nbRequests, stats), "client")
      client ! "go"
      println(s"InitialPeak client started at ${config.hostname}:${config.port} doint ${config.nbRequests} requests to a group of ${coordinators.coordinators.size} coordinators (log-level: ${config.logLevel})")
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}