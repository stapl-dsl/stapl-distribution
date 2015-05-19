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

case class OverheadCentralCoordinatorConfig(nbRuns: Int = -1, nbWarmups: Int = -1)

object OverheadCentralCoordinatorApp extends App with Logging {

  val parser = new scopt.OptionParser[OverheadCentralCoordinatorConfig]("scopt") {

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
  parser.parse(args, OverheadCentralCoordinatorConfig()) map { config =>

    val em = new EhealthEntityManager(true)
    //runDirectTest(em, config.nbRuns)

    import EhealthPolicy._
    import ClientCoordinatorProtocol._

    val system = ActorSystem("just-a-system")

    val coordinatorManager = system.actorOf(Props(classOf[FixedNumberCoordinatorsDistributedCoordinatorManager], 1), "distributed-coordinator-manager")

    val pool: AttributeDatabaseConnectionPool = new MockAttributeDatabaseConnectionPool

    val coordinator = system.actorOf(Props(classOf[DistributedCoordinator], naturalPolicy,
      1, 1, pool, coordinatorManager,
      false, false, -1, false, false, false, true /* mock evaluation */ , 0 /* 0 ms */, -1.0 ), "coordinator")

    implicit val timeout = Timeout(2.second)
    implicit val ec = system.dispatcher

    // Warmups
    println(s"Doing ${config.nbWarmups} warmup runs")
    for (i <- 0 to config.nbWarmups) {
      val request = em.randomRequest
      val f = coordinator ? request
      Await.ready(f, 180 seconds).value match {
        case Some(Success(AuthorizationDecision(id, decision))) => // nothing to do
        case x =>
          throw new RuntimeException(s"WTF did I receive: $x")
      }
    }

    // Runs
    println(s"Doing ${config.nbRuns} runs")
    val timer = new Timer
    for (i <- 0 to config.nbRuns) {
      val request = em.randomRequest
      timer time {
        val f = coordinator ? request
        Await.ready(f, 180 seconds).value match {
          case Some(Success(AuthorizationDecision(id, decision))) => // nothing to do
          case x =>
            throw new RuntimeException(s"WTF did I receive: $x")
        }
      }
    }
    println(f"Overhead of the central coordinator: mean = ${timer.mean}%2.2f ms, confInt = ${timer.confInt() * 100}%2.2f%%")
    println("#! Results")
    println(timer.toJSON())

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

