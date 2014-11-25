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
import stapl.distribution.db.AttributeDatabaseConnectionPool
import stapl.core.Attribute
import stapl.core.ConcreteValue
import scala.concurrent.Future
import scala.concurrent.blocking
import stapl.core.pdp.RequestCtx
import stapl.core.pdp.BasicEvaluationCtx
import stapl.core.pdp.RemoteEvaluator
import stapl.core.Result

/**
 * Class used for representing the actor on a machine that communicates with
 * the coordinator and employs asynchronous policy evaluation to parallelize the
 * work (instead of using multiple actors).
 */
class SimpleForeman(coordinator: ActorRef, nbParallelEvaluations: Int, policy: AbstractPolicy) extends Actor with ActorLogging {

  /**
   * Set up the PDP to be used for parallel evaluation. All state (the context)
   * is given with the request itself.
   */
  val pdp = new PDP(policy)

  /**
   * The pool of database connections.
   */
  val pool = new AttributeDatabaseConnectionPool("localhost", 3306, "stapl-attributes", "root", "root")

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
   * For communication from the future
   */
  case class PolicyEvaluationFinished

  /**
   * Start a single evaluation.
   */
  def startEvaluation(request: PolicyEvaluationRequest) = {
    // build the whole context here for every evaluation
    // TODO use attribute cache here if necessary
    val connection = pool.getConnection
    val finder = new AttributeFinder
    finder += new DatabaseAttributeFinderModule(connection)
    finder += new HardcodedEnvironmentAttributeFinderModule
    val requestCtx = new RequestCtx(request.subjectId, request.actionId, request.resourceId)
    val evaluationCtx = new BasicEvaluationCtx(request.id, requestCtx, finder, new RemoteEvaluator)
    blocking {
      val result = pdp.evaluate(evaluationCtx)
      connection.close
      coordinator ! CoordinatorForemanProtocol.PolicyEvaluationResult(request.id, result)
      self ! PolicyEvaluationFinished // this should be safe from the future
    }
  }

  /**
   * Start new evaluations depending on the size of the work queues
   * and the number of ongoing evaluations.
   */
  def startEvaluations(): Unit = {
    while (thereIsWork() && (nbOngoing < nbParallelEvaluations)) {
      nbOngoing += 1
      startEvaluation(nextRequest())
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
     * From our own future: after a policy evaluation has finished
     */
    case PolicyEvaluationFinished =>
      nbOngoing -= 1
      startEvaluations()
      // keep the coordinator up-to-date and ask for more work if needed
      if(externalWorkQ.isEmpty && internalWorkQ.isEmpty) {
        log.debug("We're out of work, notify the coordinator of this")
        coordinator ! CoordinatorForemanProtocol.ForemanIsDoneAndRequestsWork(self, nbParallelEvaluations)
      } else if (shouldPreventivelyRefill) {
        log.debug(s"We're not ouf of work yet, but ask for more anyway")
        coordinator ! CoordinatorForemanProtocol.ForemanRequestsWork(self, nbParallelEvaluations)
      }

    /**
     * To be sure
     */
    case x => log.error(s"Unknown message received: $x")
  }
}