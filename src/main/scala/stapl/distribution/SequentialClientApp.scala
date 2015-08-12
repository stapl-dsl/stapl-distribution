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
import stapl.distribution.components.SequentialClient
import stapl.distribution.components.ClientCoordinatorProtocol.AuthorizationRequest

case class SequentialClientConfig(name: String = "not-provided",
  hostname: String = "not-provided", port: Int = -1,
  coordinatorHostname: String = "not-provided", coordinatorPort: Int = -1,
  nbThreads: Int = -1, nbRequests: Int = -1, policy: String = "not-provided")

object SequentialClientApp {
  def main(args: Array[String]) {    
    val ehealthEM = stapl.distribution.db.entities.ehealth.EhealthEntityManager()
    val concEM = stapl.distribution.db.entities.concurrency.ConcurrencyEntityManager()
    val policies = Map(
        "ehealth" -> AuthorizationRequest(ehealthEM.maarten.id, "view", ehealthEM.maartenStatus.id), 
        "count" -> AuthorizationRequest(concEM.subject1.id, "blabla", concEM.resourceOfBank1.id))
    
    val parser = new scopt.OptionParser[SequentialClientConfig]("scopt") {
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
      opt[String]("coordinator-hostname") required () action { (x, c) =>
        c.copy(coordinatorHostname = x)
      } text ("The hostname of the machine on which the coordinator is running.")
      opt[Int]("coordinator-port") required () action { (x, c) =>
        c.copy(coordinatorPort = x)
      } text ("The port on which the coordinator is listening.")
      opt[Int]("nb-threads") required () action { (x, c) =>
        c.copy(nbThreads = x)
      } text ("The number of parallel client threads to start.")
      opt[Int]("nb-requests") required () action { (x, c) =>
        c.copy(nbRequests = x)
      } text ("The number of sequential requests that each client should send. 0 for infinite.")
      opt[String]("policy") required () action { (x, c) =>
        c.copy(policy = x)
      } validate { x =>
        if(policies.contains(x)) success else failure(s"Invalid policy given. Possible values: ${policies.keys}")
      } text (s"The policy for which to send requests. Valid values: ${policies.keys}")
      help("help") text ("prints this usage text")
    }
    // parser.parse returns Option[C]
    parser.parse(args, SequentialClientConfig()) map { config =>
      val defaultConf = ConfigFactory.load()
      val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = ${config.hostname}
        akka.remote.netty.tcp.port = ${config.port}
      """).withFallback(defaultConf)
      val system = ActorSystem("STAPL-client", customConf)

      val selection =
        system.actorSelection(s"akka.tcp://STAPL-coordinator@${config.coordinatorHostname}:${config.coordinatorPort}/user/coordinator")
      implicit val dispatcher = system.dispatcher
      selection.resolveOne(3.seconds).onComplete {
        case Success(coordinator) =>
          import components.ClientProtocol._
          val clients = system.actorOf(BroadcastPool(config.nbThreads).props(Props(classOf[SequentialClient], coordinator, policies(config.policy))), "clients")
          clients ! Go(config.nbRequests)
          println(s"Started ${config.nbThreads} sequential client threads that each will send ${config.nbRequests} requests at ${config.hostname}:${config.port}")
        case Failure(t) =>
          t.printStackTrace()
          system.shutdown
      }
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}