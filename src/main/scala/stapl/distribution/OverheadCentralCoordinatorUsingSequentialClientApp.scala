package stapl.distribution

import stapl.examples.policies.EhealthPolicy
import stapl.core.pdp.PDP
import stapl.core.pdp.AttributeFinder
import stapl.core.pdp.RequestCtx
import stapl.core.Result
import stapl.core.NotApplicable
import stapl.core.Deny
import stapl.core.Permit
import stapl.core.dsl.log
import stapl.core.ConcreteValue
import stapl.core.Attribute
import stapl.distribution.util.Timer
import org.joda.time.LocalDateTime
import stapl.distribution.db.entities.ehealth.EhealthEntityManager
import stapl.distribution.db.MySQLAttributeDatabaseConnectionPool
import stapl.distribution.db.DatabaseAttributeFinderModule
import stapl.distribution.db.SimpleAttributeDatabaseConnection
import grizzled.slf4j.Logging
import stapl.distribution.db.AttributeDatabaseConnectionPool
import stapl.core.Decision
import stapl.distribution.db.entities.ehealth.Person
import stapl.distribution.db.HardcodedEnvironmentAttributeFinderModule
import stapl.distribution.db.entities.EntityManager
import stapl.distribution.components.ClientCoordinatorProtocol.AuthorizationRequest
import akka.actor.Props
import akka.actor.ActorRef
import akka.actor.ActorLogging
import akka.actor.Actor
import akka.actor.Terminated
import akka.actor.ActorSystem
import akka.pattern.ask
import scala.concurrent.duration._
import akka.util.Timeout
import scala.util.{ Success, Failure }
import stapl.distribution.components.DistributedCoordinator
import stapl.distribution.components.FixedNumberCoordinatorsDistributedCoordinatorManager
import scala.concurrent.Await
import stapl.distribution.components.ClientCoordinatorProtocol
import stapl.distribution.db.MockAttributeDatabaseConnectionPool
import stapl.distribution.util.LatencyStatisticsActor
import akka.routing.BroadcastPool
import stapl.distribution.components.SequentialClientForCoordinatorGroup
import stapl.distribution.components.SimpleDistributedCoordinatorLocater
import stapl.distribution.components.ClientRegistrationProtocol

case class OverheadCentralCoordinatorUsingSequentialClientConfig(nbRuns: Int = -1, nbWarmups: Int = -1)

object OverheadCentralCoordinatorUsingSequentialClientApp extends App with Logging {

  val parser = new scopt.OptionParser[OverheadCentralCoordinatorUsingSequentialClientConfig]("scopt") {

    head("Measure the overhead of the coordinator by running mock evaluations of 0 ms through the coordinator")

    opt[Int]("nb-warmup-runs") required () action { (x, c) =>
      c.copy(nbWarmups = x)
    } text ("The number of runs to do.")

    opt[Int]("nb-runs") required () action { (x, c) =>
      c.copy(nbRuns = x)
    } text ("The number of runs to do.")

    help("help") text ("prints this usage text")
  }
  // parser.parse returns Option[C]
  parser.parse(args, OverheadCentralCoordinatorUsingSequentialClientConfig()) map { config =>

    val em = new EhealthEntityManager(true)
    //runDirectTest(em, config.nbRuns)

    import EhealthPolicy._
    import ClientCoordinatorProtocol._

    val system = ActorSystem("just-a-system")

    val coordinatorManager = system.actorOf(Props(classOf[FixedNumberCoordinatorsDistributedCoordinatorManager], 1), "distributed-coordinator-manager")

    val pool: AttributeDatabaseConnectionPool = new MockAttributeDatabaseConnectionPool

    val coordinator = system.actorOf(Props(classOf[DistributedCoordinator], naturalPolicy,
      1, 1, pool, coordinatorManager,
      false, false, -1, false, false, false, true /* mock evaluation */ , 0 /* 0 ms */, -1.0), "coordinator")

    implicit val timeout = Timeout(2.second)
    implicit val ec = system.dispatcher

    val coordinatorLocater = new SimpleDistributedCoordinatorLocater
    Await.result(coordinatorManager ? ClientRegistrationProtocol.GetListOfCoordinators, 5.seconds) match {
      case ClientRegistrationProtocol.ListOfCoordinators(coordinators) =>
        coordinatorLocater.setCoordinators(coordinators.map(_._2))
        if (coordinators.size == 0) {
          throw new RuntimeException("Received the list of coordinators, but no coordinators found. Shutting down")
          system.shutdown
        }
        println(s"Successfully received the list of coordinators: $coordinators")
      case x =>
        throw new RuntimeException(s"Failed to get the list of coordinators, shutting down. Received result: $x")
        system.shutdown
    }
    val nbClients = 10
    val stats = system.actorOf(Props(classOf[LatencyStatisticsActor], "Sequential clients", nbClients * config.nbRuns))
    val clients = system.actorOf(BroadcastPool(nbClients).props(Props(classOf[SequentialClientForCoordinatorGroup], coordinatorLocater, em, stats)))
    println(s"Started $nbClients sequential client threads that each will make ${config.nbWarmups} warmup requests and ${config.nbRuns} requests to a group of ${coordinatorLocater.coordinators.size} coordinators)")

    clients ! (config.nbWarmups, config.nbRuns)
  } getOrElse {
    // arguments are bad, error message will have been displayed
  }
}

