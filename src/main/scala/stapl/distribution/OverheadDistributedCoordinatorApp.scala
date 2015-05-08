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
import com.typesafe.config.ConfigFactory
import stapl.distribution.components.SimpleDistributedCoordinatorLocater
import stapl.distribution.components.ClientRegistrationProtocol

case class OverheadDistributedCoordinatorConfig(nbRuns: Int = -1,
  hostname: String = "not-provided", port: Int = -1,
  coordinatorManagerIP: String = "not-provided", coordinatorManagerPort: Int = -1)

object OverheadDistributedCoordinatorApp extends App with Logging {

  val parser = new scopt.OptionParser[OverheadDistributedCoordinatorConfig]("scopt") {

    head("Measure the overhead of the coordinator by running mock evaluations of 0 ms through the coordinator")

    opt[String]("hostname") required () action { (x, c) =>
      c.copy(hostname = x)
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

    opt[Int]("nb-runs") required () action { (x, c) =>
      c.copy(nbRuns = x)
    } text ("The number of runs to do.")

    help("help") text ("prints this usage text")
  }
  // parser.parse returns Option[C]
  parser.parse(args, OverheadDistributedCoordinatorConfig()) map { config =>

    val em = new EhealthEntityManager(true)
    //runDirectTest(em, config.nbRuns)

    import EhealthPolicy._
    import ClientCoordinatorProtocol._

    val defaultConf = ConfigFactory.load()
    val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = ${config.hostname}
        akka.remote.netty.tcp.port = ${config.port}
        akka.loglevel = OFF
      """).withFallback(defaultConf)
    val system = ActorSystem("STAPL-client", customConf)

    val coordinatorLocater = new SimpleDistributedCoordinatorLocater
    val selection = system.actorSelection(s"akka.tcp://STAPL-coordinator@${config.coordinatorManagerIP}:${config.coordinatorManagerPort}/user/distributed-coordinator-manager")
    implicit val dispatcher = system.dispatcher
    implicit val timeout = Timeout(5.second)
    val coordinatorManager = Await.result(selection.resolveOne(3.seconds), 5.seconds)
    Await.result(coordinatorManager ? ClientRegistrationProtocol.GetListOfCoordinators, 5.seconds) match {
      case ClientRegistrationProtocol.ListOfCoordinators(coordinators) =>
        coordinatorLocater.setCoordinators(coordinators.map(_._2))
        if (coordinators.size == 0) {
          println("Received the list of coordinators, but no coordinators found. Shutting down")
          system.shutdown
        }
        println(s"Successfully received the list of coordinators: $coordinators")
      case x =>
        println(s"Failed to get the list of coordinators, shutting down. Received result: $x")
        system.shutdown
    }

    val timer = new Timer("Through distributed coordinator")
    for (i <- 0 to config.nbRuns) {
      val request = em.randomRequest
      timer time {
        val coordinator = coordinatorLocater.getCoordinatorFor(request)
        val f = coordinator ? request
        Await.ready(f, 180 seconds).value match {
          case Some(Success(AuthorizationDecision(decision))) => // nothing to do
          case x =>
            throw new RuntimeException(s"WTF did I receive: $x")
        }
      }
    }
    println(f"Overhead of the distributed coordinator: mean = ${timer.mean}%2.2f ms, confInt = ${timer.confInt() * 100}%2.2f")

    system.shutdown
  } getOrElse {
    // arguments are bad, error message will have been displayed
  }

  /**
   * 1. Call the PDP directly
   *
   * def runDirectTest(em: EhealthEntityManager, nbRuns: Int) = {
   * import EhealthPolicy._
   * val pdp = new PDP(naturalPolicy)
   * val timer = new Timer("Directly to PDP")
   * for (i <- 0 to nbRuns) {
   * val request = em.randomRequest
   * timer time {
   * pdp.evaluate(request.subjectId, request.actionId, request.resourceId, request.extraAttributes: _*)
   * }
   * }
   * println(f"Directly to PDP: mean = ${timer.mean}%2.2f ms, confInt = ${timer.confInt() * 100}%2.2f")
   * }
   */

  def assert(wanted: Result, actual: Result)(implicit subject: Person, action: String, resource: stapl.distribution.db.entities.ehealth.Resource, extraAttributes: List[(stapl.core.Attribute, stapl.core.ConcreteValue)]) = {
    if (wanted.decision != actual.decision) {
      throw new AssertionError(s"Wanted ${wanted.decision} but was ${actual.decision} for request (${subject.id},$action,${resource.id},$extraAttributes)")
    } else {
      debug(s"Request (${subject.id},$action,${resource.id},$extraAttributes) OK")
    }
  }
}

