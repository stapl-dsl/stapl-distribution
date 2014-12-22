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
import stapl.distribution.components.RemoteConcurrentCoordinatorGroup
import stapl.distribution.components.ClientCoordinatorProtocol.AuthorizationRequest
import stapl.distribution.util.StatisticsActor

case class SequentialClientForConcurrentCoordinatorsConfig(name: String = "not-provided",
  hostname: String = "not-provided", port: Int = -1,
  nbThreads: Int = -1, nbRequests: Int = -1, policy: String = "not-provided",
  coordinatorManagerHostname: String = "not-provided", coordinatorManagerPort: Int = -1)

object SequentialClientForConcurrentCoordinatorsApp {
  def main(args: Array[String]) {
    
    
    val ehealthEM = stapl.distribution.db.entities.ehealth.EntityManager()
    val concEM = stapl.distribution.db.entities.concurrency.EntityManager()
    
    val parser = new scopt.OptionParser[SequentialClientForConcurrentCoordinatorsConfig]("scopt") {
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
      
      opt[Int]("nb-threads") required () action { (x, c) =>
        c.copy(nbThreads = x)
      } text ("The number of parallel client threads to start.")
      
      opt[Int]("nb-requests") required () action { (x, c) =>
        c.copy(nbRequests = x)
      } text ("The number of sequential requests that each client should send. 0 for infinite.")
      
      help("help") text ("prints this usage text")
    }
    // parser.parse returns Option[C]
    parser.parse(args, SequentialClientForConcurrentCoordinatorsConfig()) map { config =>
      val defaultConf = ConfigFactory.load()
      val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = ${config.hostname}
        akka.remote.netty.tcp.port = ${config.port}
      """).withFallback(defaultConf)
      val system = ActorSystem("STAPL-client", customConf)

      val selection =
        system.actorSelection(s"akka.tcp://STAPL-coordinator@${config.coordinatorManagerHostname}:${config.coordinatorManagerPort}/user/coordinator-manager")
      implicit val dispatcher = system.dispatcher
      selection.resolveOne(3.seconds).onComplete {
        case Success(coordinatorManager) =>
          import components.ClientProtocol._
          
          val coordinators = new RemoteConcurrentCoordinatorGroup(system, config.coordinatorManagerHostname, config.coordinatorManagerPort)      
          val stats = system.actorOf(Props(classOf[StatisticsActor],1000,10))
          val clients = system.actorOf(BroadcastPool(config.nbThreads).props(Props(classOf[SequentialClientForConcurrentCoordinators], coordinators, stats)), "clients")
          clients ! Go(config.nbRequests)
          println(s"Started ${config.nbThreads} sequential client threads that each will send ${config.nbRequests} requests to ${coordinators.coordinators.size} coordinators running at ${config.hostname}:${config.port}")
        case Failure(t) =>
          t.printStackTrace()
          system.shutdown
      }
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}