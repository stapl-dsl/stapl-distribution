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

import akka.actor.ActorRef
import stapl.core.Permit
import stapl.core.Result
import akka.actor.ActorSystem
import scala.concurrent.duration._
import scala.util.{ Success, Failure }
import akka.actor.Props
import akka.actor.actorRef2Scala
import scala.math.BigInt.int2bigInt
import stapl.distribution.components.SimpleForeman
import com.typesafe.config.ConfigFactory
import stapl.examples.policies.EhealthPolicy

case class SimpleForemanConfig(name: String = "not-provided",
  hostname: String = "not-provided", port: Int = -1,
  coordinatorHostname: String = "not-provided", coordinatorPort: Int = -1,
  nbParallelEvaluations: Int = -1)

object SimpleForemanApp {
  def main(args: Array[String]) {

    val parser = new scopt.OptionParser[SimpleForemanConfig]("scopt") {
      head("STAPL - coordinator")
      opt[String]("name") required () action { (x, c) =>
        c.copy(name = x)
      } text ("The name of this foreman. This is used for debugging.")
      opt[String]("hostname") required () action { (x, c) =>
        c.copy(hostname = x)
      } text ("The hostname of the machine on which this foreman is run. This hostname will be used by other actors in their callbacks, so it should be externally accessible if you deploy the components on different machines.")
      opt[Int]("port") required () action { (x, c) =>
        c.copy(port = x)
      } text ("The port on which this foreman will be listening. 0 for a random port")
      opt[String]("coordinator-hostname") required () action { (x, c) =>
        c.copy(coordinatorHostname = x)
      } text ("The hostname of the machine on which the coordinator is running.")
      opt[Int]("coordinator-port") required () action { (x, c) =>
        c.copy(coordinatorPort = x)
      } text ("The port on which the coordinator is listening.")
      opt[Int]("nb-parallel-evaluations") required () action { (x, c) =>
        c.copy(nbParallelEvaluations = x)
      } text ("The maximal number of parallel policy evaluations that this foreman can perform at any single moment in time.")
      help("help") text ("prints this usage text")
    }
    // parser.parse returns Option[C]
    parser.parse(args, SimpleForemanConfig()) map { config =>
      val defaultConf = ConfigFactory.load()
      val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = ${config.hostname}
        akka.remote.netty.tcp.port = ${config.port}
      """).withFallback(defaultConf)
      val system = ActorSystem("Worker", customConf)

      val selection =
        system.actorSelection(s"akka.tcp://STAPL-coordinator@${config.coordinatorHostname}:${config.coordinatorPort}/user/coordinator")
      implicit val dispatcher = system.dispatcher
      selection.resolveOne(3.seconds).onComplete {
        case Success(coordinator) =>
          val foreman = system.actorOf(Props(classOf[SimpleForeman], coordinator, config.nbParallelEvaluations, EhealthPolicy.naturalPolicy), "foreman")
          println(s"SimpleForman ${config.name} up and running at ${config.hostname}:${config.port} with ${config.nbParallelEvaluations} maximum parallel evaluations")
        case Failure(t) =>
          t.printStackTrace()
          system.shutdown
      }
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}