package stapl.distribution.components

import stapl.distribution.db.AttributeDatabaseConnection
import akka.actor.ActorRef
import akka.actor.Actor
import akka.actor.ActorLogging
import stapl.distribution.util.ThroughputStatistics
import stapl.core.pdp.SimpleTimestampGenerator
import akka.actor.Props
import stapl.distribution.db.AttributeDatabaseConnectionPool
import stapl.distribution.components.CoordinatorForemanProtocol.PolicyEvaluationResult
import stapl.core.ConcreteChangeAttributeObligationAction
import stapl.core.ConcreteObligationAction
import scala.collection.mutable.Queue
import stapl.core.AbstractPolicy
import stapl.distribution.util.ThroughputAndLatencyStatistics
import stapl.core.Deny

/**
 *  This is a coordinator that is part of a distributed group of coordinators. This means
 *  that each coordinator can be located on a different node or multiple such coordinators
 *  can be started on a single node in different VMs.
 *
 *  As opposed to the normal coordinator and the concurrent coordinator, the distributed
 *  coordinator handles multiple workers itself, i.e., without a foreman.
 *
 *  Notice: for now, we do not support adding or removing coordinators from the group
 *  at run-time, i.e., all coordinators should be set up before the first request is sent
 *  in order to avoid concurrency issues.
 */
class DistributedCoordinator(coordinatorId: Long, policy: AbstractPolicy, nbWorkers: Int, nbUpdateWorkers: Int,
  pool: AttributeDatabaseConnectionPool, coordinatorManager: CoordinatorLocater,
  enableStatsIn: Boolean = false, enableStatsOut: Boolean = false, statsOutInterval: Int = 2000,
  enableStatsWorkers: Boolean = false, enableStatsDb: Boolean = false,
  mockDecision: Boolean = false, mockEvaluation: Boolean = false,
  mockEvaluationDuration: Int = 0) extends Actor with ActorLogging {

  import ClientCoordinatorProtocol._
  import ConcurrentCoordinatorProtocol._
  import InternalCoordinatorProtocol._

  import scala.collection.mutable.Map

  /**
   * Holds the mapping between the clients and the authorization requests
   * they sent.
   */
  private val clients: Map[String, ActorRef] = Map()

  /**
   * Some statistics of the throughput
   */
  //private val stats = new ThroughputStatistics
  private val inputStats = new ThroughputStatistics("Coordinator - in", 2000, 10, enableStatsIn)
  private val outputStats = new ThroughputStatistics("Coordinator - out", statsOutInterval, 10, enableStatsOut)
  private val workerStats = new ThroughputAndLatencyStatistics("Workers", 2000, 10, enableStatsWorkers)

  /**
   * A timestamp generator for generating ids for the evaluations.
   */
  private val timestampGenerator = new SimpleTimestampGenerator

  def constructNextId() = s"$coordinatorId:${timestampGenerator.getTimestamp}"

  /**
   * *********************************
   * The queues of work for our Workers: one queue of work received by the
   * foreman, one queue of work received by the workers (intermediate requests).
   */
  val externalWorkQ = Queue.empty[(PolicyEvaluationRequest, ActorRef)]
  val internalWorkQ = Queue.empty[(PolicyEvaluationRequest, ActorRef)]

  /**
   * *********************************
   * Our workers
   */
  val workers = new WorkerManager
  // create our workers
  1 to nbWorkers foreach { _ =>
    workers += context.actorOf(Props(classOf[Worker], self, policy, null, pool.getConnection, enableStatsDb, 
        mockEvaluation, mockEvaluationDuration)) // TODO pass policy and attribute cache
  }

  /**
   * Notifies workers that there's work available, provided they're
   * not already working on something
   */
  def notifyWorkers(): Unit = {
    if (!(externalWorkQ.isEmpty && internalWorkQ.isEmpty)) {
      // we have some work, be it received from a worker or from a foreman
      workers.idle foreach { _ ! ForemanWorkerProtocol.WorkIsReady }
    }
  }

  /**
   * *********************************
   * Our UpdateWorkers
   */
  private val updateWorkers = scala.collection.mutable.ListBuffer[ActorRef]()
  // private val db = AttributeDatabaseConnectionPool("localhost", 3306, "stapl-attributes", "root", "root", false /* we want to write => NOT readonly */)
  1 to nbUpdateWorkers foreach { _ =>
    log.debug("Setting up another update worker")
    updateWorkers += context.actorOf(Props(classOf[UpdateWorker], self, pool.getConnection))
    log.debug("Finished setting up another update worker")
  }

  /**
   * *********************************
   * Our ConcurrencyController
   */
  private val concurrencyController = new ConcurrentConcurrencyController(self, updateWorkers.toList, log)

  /**
   * A mapping of id -> original request for restarting requests efficiently.
   */
  private val id2original = scala.collection.mutable.Map[String, PolicyEvaluationRequest]()

  /**
   * *********************************
   * ACTOR FUNCTIONALITY
   */
  def receive = {

    /**
     * From a Worker.
     *
     * Give him more work if we have some.
     */
    case ForemanWorkerProtocol.WorkerIsDoneAndRequestsWork(worker) =>
      log.debug(s"A worker finished his work: $worker")
      workerStats.stop(worker)
      workers.setIdle(worker)
      self ! ForemanWorkerProtocol.WorkerRequestsWork(worker)

    /**
     * From a Worker.
     *
     * Give him work if we have some.
     */
    case ForemanWorkerProtocol.WorkerRequestsWork(worker) =>
      if (workers.idleWorkers.contains(worker)) {
        log.debug(s"An idle worker requests work: $worker => let's see whether I can send him some work")
        // our prioritization between internal requests and external requests:
        // give internal requests priority in order to keep the total latency
        // of policy evaluations minimal
        if (!internalWorkQ.isEmpty) {
          val (request, coordinator) = internalWorkQ.dequeue
          worker ! ForemanWorkerProtocol.WorkToBeDone(request, coordinator)
          workers.setBusy(worker)
          workerStats.start(worker)
          log.debug(s"[Evaluation ${request.id}] Sent request to worker $worker for evaluation")
        } else if (!externalWorkQ.isEmpty) {
          val (request, coordinator) = externalWorkQ.dequeue
          worker ! ForemanWorkerProtocol.WorkToBeDone(request, coordinator)
          workers.setBusy(worker)
          workerStats.start(worker)
          log.debug(s"[Evaluation ${request.id}] Sent request to worker $worker for evaluation")
        }
      } else {
        log.debug(s"A busy worker requests work: $worker => not sending him work")
      }

    /**
     * For a coordinator managing (at least) the SUBJECT.
     *
     * A clients sends an authorization request.
     */
    case AuthorizationRequest(subjectId, actionId, resourceId, extraAttributes) =>
      inputStats.tick
      val client = sender
      if (mockDecision) {
        client ! AuthorizationDecision(Deny)
      } else {
        // this is a request from a client => construct an id
        val id = constructNextId()
        // store the client to forward the result later on
        clients(id) = client
        // construct the internal request
        val original = new PolicyEvaluationRequest(id, Top, subjectId, actionId, resourceId, extraAttributes)
        // store the original
        id2original(id) = original
        // In case of intelligent clients, we should only have received this request
        // if we should manage the subject of the request. In case of unintelligent 
        // clients, we could forward the request to the appropriate coordinator.
        coordinatorManager.whatShouldIManage(self, original) match {
          case BOTH =>
            log.debug(s"[Evaluation ${id}] Received authorization request: ($subjectId, $actionId, $resourceId) from $client (I should manage both => queuing it immediately)")
            // add the attributes according to our administration
            val updated = concurrencyController.startForBoth(original)
            // forward the updated request to one of our workers
            externalWorkQ.enqueue((updated, self))
            notifyWorkers
          case ONLY_SUBJECT =>
            log.debug(s"[Evaluation ${id}] Received authorization request: ($subjectId, $actionId, $resourceId) from $client (I should manage only the SUBJECT => contacting the other coordinator)")
            val updated = concurrencyController.startForSubject(original)
            // ask the other coordinator to start the actual evaluation 
            // (the result will be sent directly to us)
            coordinatorManager.getCoordinatorForResource(resourceId) ! ManageResourceAndStartEvaluation(updated)
          case _ =>
            // we could forward here. For now: log an error
            log.error(s"[Evaluation ${id}] Received authorization request for which I am not responsible: ${AuthorizationRequest(subjectId, actionId, resourceId, extraAttributes)}")
        }
      }

    /**
     * For a coordinator managing the RESOURCE.
     *
     * Another coordinator sends a request for us to manage the RESOURCE and evaluate
     * the given request.
     */
    case ManageResourceAndStartEvaluation(updatedRequest) =>
      // log
      log.debug(s"[Evaluation ${updatedRequest.id}] Queueing $updatedRequest via $sender (I should manage only the RESOURCE)")
      // add the attributes according to our own administration
      val updatedAgain = concurrencyController.startForResource(updatedRequest)
      // forward the request to one of our workers
      // note: the result should be sent to the other coordinator (i.e., the sender
      // of this StartRequestAndManageResource request)
      externalWorkQ.enqueue((updatedAgain, sender))
      notifyWorkers

    /**
     * For a coordinator managing the RESOURCE.
     *
     * The commit of the evaluation failed at the coordinator that manages the subject
     * of the request and that coordinator has restarted the evaluation.
     */
    case ManageResourceAndRestartEvaluation(updatedRequest) =>
      // log
      log.debug(s"[Evaluation #${updatedRequest.id}] Queueing $updatedRequest via $sender (I should manage only the RESOURCE)")
      // add the attributes according to our own administration
      val updatedAgain = concurrencyController.restartForResource(updatedRequest)
      // forward the updated request to our foreman manager
      // note: the result should be sent to the other coordinator (i.e., the sender
      // of this StartRequestAndManageResource request)
      externalWorkQ.enqueue((updatedAgain, sender))
      notifyWorkers

    /**
     * For a coordinator managing the SUBJECT.
     *
     * A worker has sent us the result of the policy evaluation that the other coordinator
     * started => try to commit it.
     */
    case result: PolicyEvaluationResult =>
      val id = result.id
      val original = id2original(id)
      log.debug(s"[Evaluation ${id}] Received authorization decision: ($id, $result) Trying to commit.")
      // check what we manage: SUBJECT or RESOURCE or BOTH
      // Note: we have to take into account the special case in which we manage 
      //			BOTH the SUBJECT and the RESOURCE in order to be able to remove 
      //			the state if we can commit (i.e., removing the state in case that
      //			we manage both the subject and the resource would lead to errors if we
      //			then send a message to ourselves to commit for the subject/resource
      //			after having committed for the resource/subject).
      coordinatorManager.whatShouldIManage(self, original) match {
        case BOTH =>
          // try to commit for both the subject and the resource
          if (concurrencyController.commitForBoth(result)) {
            log.debug(s"[Evaluation ${id}] The commit for request $id SUCCEEDED for BOTH")
            // the commit succeeded => remove the request from our administration and 
            // return the result to the client
            id2original.remove(id)
            val client = clients(id)
            client ! AuthorizationDecision(result.result.decision)
            clients.remove(id)
            outputStats.tick
          } else {
            log.debug(s"[Evaluation ${id}] The commit for request $id FAILED for BOTH, restarting it")
            // the commit failed => restart the evaluation (add the necessary
            // attributes to the original again)
            // note: the concurrency controller will have already cleaned up its state
            // in the commitForBoth() method
            val updated = concurrencyController.startForBoth(original)
            externalWorkQ.enqueue((updated, self))
            notifyWorkers
          }
        case ONLY_SUBJECT =>
          // try to commit for the subject
          if (concurrencyController.commitForSubject(result)) {
            log.debug(s"[Evaluation ${id}] The commit for request $id SUCCEEDED for the SUBJECT, checking with the other coordinator")
            // only forward the resource attribute updates, we have already processed the rest
            def isResourceChangeAttributeObligationAction(x: ConcreteObligationAction): Boolean = {
              x match {
                case change: ConcreteChangeAttributeObligationAction =>
                  change.attribute.cType match {
                    case stapl.core.RESOURCE => true
                    case _ => false
                  }
                case _ => false
              }
            }
            val filtered = result.result.obligationActions filter (isResourceChangeAttributeObligationAction(_))
            val filteredResult = PolicyEvaluationResult(result.id, result.result.copy(obligationActions = filtered))
            // ...and ask the other coordinator to commit the rest 
            // Note: the result of getCoordinatorForResource() should be the same as at 
            // the start (in other words, the system currently does not support adding or
            // removing coordinators at run-time)
            coordinatorManager.getCoordinatorForResource(original.resourceId) ! TryCommitForResource(filteredResult)
          } else {
            log.debug(s"[Evaluation ${id}] The commit for request $id FAILED for the SUBJECT, restarting it")
            // the commit failed => restart the evaluation (add the necessary
            // attributes to the original again)
            // note: the concurrency controller will have already cleaned up its state
            // in the commitForSubject() method
            val original = id2original(id)
            val updated = concurrencyController.startForSubject(original)
            // ask the other coordinator to restart as well. This coordinator will 
            // redo the actual evaluation (but the result will be sent to us).
            coordinatorManager.getCoordinatorForResource(original.resourceId) ! ManageResourceAndRestartEvaluation(updated)
          }
        case x =>
          // this should never happen
          throw new RuntimeException(s"I don't know what to do with this. Original = $original. I should manage: $x")
      }

    /**
     * For a coordinator managing the RESOURCE.
     *
     * The other coordinator has received the result of the policy evaluation from the
     * worker, the commit at his side is successful and he now asks us whether we can
     * commit the evaluation as well.
     *
     * If the commit fails, we remove all state so that the other coordinator can just
     * restart the evaluation by just sending us the start command again.
     */
    case TryCommitForResource(result) =>
      val id = result.id
      log.debug(s"[Evaluation ${id}] Received result to commit evaluation $id for the resource")
      if (concurrencyController.commitForResource(result)) {
        log.debug(s"[Evaluation ${id}] The commit for request $id SUCCEEDED for the RESOURCE")
        // Note: there are no tentative updates to be done here: the other coordinator
        // saved its updates tentatively, we can process our resource updates immediately
        // (actually, the concurrency controller will have done that in commitForResource()).
        // So, we just have to answer to the sender that the commit succeeded.
        sender ! CommitForResourceSucceeded(result)
      } else {
        log.debug(s"[Evaluation ${id}] The commit for request $id FAILED for the RESOURCE => cleaning up " +
          "internal state (we will build it up again when the coordinator askes us to start " +
          "the evaluation again later on)")
        // 1. Clean up: we do not have state ourselves for this if we only manage the 
        // RESOURCE and the concurrency controller will already have cleaned up its state,
        // so nothing to do for us actually.
        // 2. Answer to the sender that the commit failed.
        sender ! CommitForResourceFailed(result)
      }

    /**
     * For a coordinator managing the SUBJECT.
     *
     * The local commit for the subject succeeded, we then sent a request to commit for
     * the resource to the other coordinator and apparently, that succeeded as well. So
     * we can now finalize our tentative attribute updates and return the result to the
     * client.
     */
    case CommitForResourceSucceeded(result) =>
      val id = result.id
      log.debug(s"[Evaluation ${id}] The other coordinator sent that the commit for request $id SUCCEEDED for the RESOURCE => wrap up")
      // 1. remove the request from our administration
      id2original.remove(id)
      // 2. finalize tentative attribute updates
      // TODO
      // 3. send result to client
      val client = clients(id)
      client ! AuthorizationDecision(result.result.decision)
      clients.remove(id)
      outputStats.tick

    /**
     * For a coordinator managing the SUBJECT.
     *
     * The local commit for the subject succeeded, we then sent a request to commit for
     * the resource to the other coordinator and apparently, but that failed apparently.
     * The other coordinator already cleaned up its administration for this evaluation,
     * we have to do the same (and especially: mark evaluations that used our tentative
     * attribute updates to be restarted) and restart the evaluation.
     */
    case CommitForResourceFailed(result) =>
      val id = result.id
      log.debug(s"[Evaluation ${id}] The other coordinator sent that the commit for request $id FAILED for the RESOURCE => restart")
      // the commit succeeded   
      // 1. destroy tentative attribute updates and mark evaluations that used them to be restarted as well
      // TODO
      // TODO Notice that you can actually restart the evaluation immediately, but you do
      //		have to be able to distinguish the result from the former and the latter
      //		evaluation later on. You can do this based on the value of the employed 
      //		attributes. 
      // 2. restart
      val original = id2original(id)
      val updated = concurrencyController.restartForSubject(original)
      // ask the other coordinator to restart as well. This coordinator will 
      // start the actual evaluation (but the result will be sent to us)
      coordinatorManager.getCoordinatorForResource(original.resourceId) ! ManageResourceAndStartEvaluation(updated)

    /**
     * An update worker has finished an update.
     */
    case UpdateFinished(updateWorker) =>
      // just forward this message to the concurrency controller (which is 
      // not an actor...)
      concurrencyController.updateFinished(updateWorker)

    /**
     * Just to be sure
     */
    case x =>
      log.warning(s"Unknown message received: $x")

  }

  log.debug(s"$self: I'm alive!")
}