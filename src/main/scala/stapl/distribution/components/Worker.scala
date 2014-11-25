package stapl.distribution.components

import akka.actor.ActorSystem
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Future
import org.joda.time.LocalDateTime
import stapl.core.AbstractPolicy
import stapl.distribution.concurrency.ConcurrentAttributeCache
import stapl.distribution.db.AttributeDatabaseConnection
import stapl.core.pdp.AttributeFinder
import stapl.distribution.db.DatabaseAttributeFinderModule
import stapl.core.pdp.PDP
import stapl.core.Result
import stapl.core.Permit
import stapl.distribution.db.HardcodedEnvironmentAttributeFinderModule
import stapl.distribution.db.LegacyAttributeDatabaseConnection

/**
 * The Scala actor that wraps a PDP and is able to evaluate policies on request of a Foreman.
 */
class Worker(coordinator: ActorRef, foreman: ActorRef, policy: AbstractPolicy, cache: ConcurrentAttributeCache) extends Actor with ActorLogging {

  val db = new LegacyAttributeDatabaseConnection("localhost", 3306, "stapl-attributes", "root", "root")
  db.open

  // TODO use attribute cache here if necessary
  val finder = new AttributeFinder
  finder += new DatabaseAttributeFinderModule(db)
  finder += new HardcodedEnvironmentAttributeFinderModule
  val pdp = new PDP(policy, finder)

  import ForemanWorkerProtocol._

  /**
   *  This is the state we're in when we're working on something.
   *  In this state we can deal with messages in a much more
   *  reasonable manner.
   */
  def working(work: Any): Receive = {
    case WorkIsReady => // Pass... we're already working
    case NoWorkToBeDone => // Pass... we're already working
    case WorkToBeDone(_) => // Pass... we shouldn't even get this 
      log.error("Yikes. Master told me to do work, while I'm working.")
    case x => log.error(s"Unknown message received: $x")
  }

  /**
   *  In this state we have no work to do.  There really are only
   *  two messages that make sense while we're in this state, and
   *  we deal with them specially here.
   */
  def idle: Receive = {
    case WorkIsReady => // Coordinator says there's work to be done, let's ask for it
      log.debug("Requesting work")
      foreman ! WorkerRequestsWork(self)
    case WorkToBeDone(request: PolicyEvaluationRequest) => // Send the work off to the implementation
      log.debug(s"Got work: $request")
      context.become(working(request))
      processRequest(request)
    case NoWorkToBeDone => // We asked for work, but either someone else got it first, or
    // there's literally no work to be done
    case x => log.error(s"Unknown message received: $x")
  }

  def receive = idle

  /**
   *
   */
  private def processRequest(request: PolicyEvaluationRequest): Unit = {
    // TODO implement evaluating the policy asked for by the request
    // TODO implement extra attributes
    val result = pdp.evaluate(request.subjectId, request.actionId, request.resourceId)
    // pass the decision directly to the coordinator...
    coordinator ! CoordinatorForemanProtocol.PolicyEvaluationResult(request.id, result)
    // ... and request new work from the foreman...
    foreman ! WorkerIsDoneAndRequestsWork(self)
    // ... and change our mode
    context.become(idle)
  }
}