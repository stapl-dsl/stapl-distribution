package stapl.distribution.components

import akka.actor.ActorRef
import stapl.core.Permit
import stapl.core.Result
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import scala.concurrent.duration._
import scala.util.{ Success, Failure }
import akka.actor.Props
import CoordinatorForemanProtocol.PolicyEvaluationResult
import akka.actor.actorRef2Scala
import scala.math.BigInt.int2bigInt
import akka.actor.ActorLogging
import akka.actor.Actor
import scala.collection.mutable.Queue
import scala.collection.mutable.Set
import stapl.core.AbstractPolicy
import stapl.distribution.db.AttributeDatabaseConnection
import stapl.core.pdp.AttributeFinder
import stapl.distribution.db.DatabaseAttributeFinderModule
import stapl.distribution.db.HardcodedEnvironmentAttributeFinderModule
import stapl.core.pdp.PDP
import scala.util.{ Try, Success, Failure }
import scala.util.Failure
import concurrent.ExecutionContext.Implicits.global

/**
 * Class used for representing the actor on a machine that communicates with
 * the coordinator and employs asynchronous policy evaluation to parallelize the
 * work (instead of using multiple actors).
 */
class SimpleForeman(coordinator: ActorRef, nbParallelEvaluations: Int, policy: AbstractPolicy) extends Actor with ActorLogging {

  /**
   * Set up the PDP
   */
  val db = new AttributeDatabaseConnection("localhost", 3306, "stapl-attributes", "root", "root")
  db.open
  // TODO use attribute cache here if necessary
  val finder = new AttributeFinder
  finder += new DatabaseAttributeFinderModule(db)
  finder += new HardcodedEnvironmentAttributeFinderModule
  val pdp = new PDP(policy, finder)

  /**
   * The queues of work for our Workers: one queue of work received by the
   * foreman, one queue of work received by the workers (intermediate requests).
   */
  val externalWorkQ = Queue.empty[PolicyEvaluationRequest]
  val internalWorkQ = Queue.empty[PolicyEvaluationRequest]
  def thereIsWork() = !(externalWorkQ.isEmpty && internalWorkQ.isEmpty)
  /**
   * Returns the next request to be evaluated. Throws a NoSuchElementException
   * is the queues are empty. SO ONLY CALL THIS METHOD WHEN thereIsWork()
   */
  def nextRequest(): PolicyEvaluationRequest = {
    // our prioritization between internal requests and external requests:
    // give internal requests priority in order to keep the total latency
    // of policy evaluations minimal
    if (!internalWorkQ.isEmpty) {
      internalWorkQ.dequeue
    } else {
      externalWorkQ.dequeue
    }
  }

  /**
   * The number of parallel evaluations currently going on.
   */
  var nbOngoing = 0

  /**
   * Returns whether we should preventively request more work because of
   * the current contents of the queues and the current number of workers.
   */
  def shouldPreventivelyRefill =
    // our strategy: count the number of requests from the foreman and the
    // number of requests of internal workers and compare that to the number of
    // workers at hand so that there is always new work for the workers to work on
    // when they finish. However, requests from the foreman are likely to 
    // take longer than intermediate policy evaluation requests of the workers,
    // so weigh those less.
    (externalWorkQ.size + math.floor(internalWorkQ.size / 2.0)) <= nbParallelEvaluations

  /**
   *  Notify the coordinator that we're alive
   */
  override def preStart() = {
    coordinator ! CoordinatorForemanProtocol.ForemanCreated(self)
  }

  /**
   * Start new evaluations depending on the size of the work queues
   * and the number of ongoing evaluations.
   */
  def startEvaluations(): Unit = {
    while (thereIsWork() && (nbOngoing < nbParallelEvaluations)) {
      val request = nextRequest()
      nbOngoing += 1
      pdp.evaluateAsync(request.subjectId, request.actionId, request.resourceId) onComplete {
        _ match {
          case Failure(e) =>
            nbOngoing -= 1
            // log the error
            log.error(s"There was an error in evaluating request ${request.id}", e)
          case Success(result) =>
            nbOngoing -= 1
            // forward the result to the coordinator and start new evaluations
            // if possible
            coordinator ! CoordinatorForemanProtocol.PolicyEvaluationResult(request.id, result)
            startEvaluations()
        }
      }
    }
  }

  /**
   *  This is the state we're in when we're working on something.
   *  In this state we can deal with messages in a much more
   *  reasonable manner.
   */
  def receive = {
    /**
     * From the Coordinator.
     *
     * Fetch work if we have spare workers
     */
    case CoordinatorForemanProtocol.WorkIsReady =>
      log.debug(s"The coordinator said that work is ready")
      // TODO now we request work for the idle workers, but probably
      // we should request some more so that workers that finish can
      // immediately start working on something
      coordinator ! CoordinatorForemanProtocol.ForemanRequestsWork(self, nbParallelEvaluations - nbOngoing)

    /**
     * From the Coordinator.
     *
     * We will only receive this when we asked for it => add the work to the queue
     */
    case CoordinatorForemanProtocol.WorkToBeDone(requests: List[PolicyEvaluationRequest]) =>
      log.debug(s"The coordinator sent work: $requests")
      externalWorkQ ++= requests
      startEvaluations()

    /**
     * To be sure
     */
    case x => log.error(s"Unknown message received: $x")
  }
}