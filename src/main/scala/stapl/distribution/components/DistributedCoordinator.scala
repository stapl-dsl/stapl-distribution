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
import scala.collection.mutable.ListBuffer
import akka.util.Timeout
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Random
import scala.util.Success
import akka.pattern.ask
import stapl.distribution.util.Tracer

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
 *
 *  THIS CLASS IS THE MAIN COORDINATOR CLASS, EVEN THOUGH IT IS CALLED "DISTRIBUTED"COORDINATOR.
 *  THIS CLASS CAN BE USED AS A STAND-ALONE COORDINATOR OR IN CONJUNCTION WITH OTHER COORDINATORS.
 *  THIS CLASS CAN MANAGE ITS OWN WORKERS AND MANAGE FOREMEN THAT MANAGE WORKERS.
 *
 *  TODO The foremen functionality is not integrated completely yet, e.g., the stats. Check and complete!
 */
class DistributedCoordinator(policy: AbstractPolicy, nbWorkers: Int, nbUpdateWorkers: Int,
  pool: AttributeDatabaseConnectionPool, coordinatorManager: ActorRef,
  enableStatsIn: Boolean = false, enableStatsOut: Boolean = false, statsOutInterval: Int = 2000,
  enableStatsWorkers: Boolean = false, enableStatsDb: Boolean = false,
  mockDecision: Boolean = false, mockEvaluation: Boolean = false,
  mockEvaluationDuration: Int = 0, mockChanceOfConflict: Double = -1) extends Actor with ActorLogging {

  import ClientCoordinatorProtocol._
  import ConcurrentCoordinatorProtocol._
  import InternalCoordinatorProtocol._
  import CoordinatorForemanProtocol._
  import scala.collection.mutable.Map

  // start by registering to the DistributedCoordinatorManager
  // FAIL IF THE REGISTRATION FAILS
  var coordinatorId = -1
  val coordinatorLocater = new SimpleDistributedCoordinatorLocater
  implicit val timeout = Timeout(5.second)
  implicit val ec = context.dispatcher
  Await.result(coordinatorManager ? DistributedCoordinatorRegistrationProtocol.Register(self), 5 seconds) match {
    case DistributedCoordinatorRegistrationProtocol.AckOfRegister(id, coordinators) =>
      coordinatorId = id
      coordinatorLocater.setCoordinators(coordinators.map(_._2))
      log.info(s"Successfully registered with coordinatorManager. I have received id $id and the list of coordinators: $coordinators")
    case x =>
      log.error(s"Failed to register with the coordinator manager, shutting down. Received result: $x")
      // synchronous suicide
      throw new RuntimeException(s"Failed to register with the coordinator manager, shutting down. Received result: $x")
  }

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
  private val workerStats = new ThroughputAndLatencyStatistics("Workers", 2000, 10, false, enableStatsWorkers)

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
   * ********************************
   * Our foremen
   */

  /**
   *  Holds known foremen and what they may be working on
   */
  private val foremen = new ForemanAdministration

  /**
   * Notifies workers AND FOREMEN that there's work available, provided they're
   * not already working on something
   */
  def notifyWorkers(): Unit = {
    if (!(externalWorkQ.isEmpty && internalWorkQ.isEmpty)) {
      // we have some work, be it received from a worker or from a foreman
      workers.idle foreach { _ ! ForemanWorkerProtocol.WorkIsReady }
      foremen.idle foreach { _ ! CoordinatorForemanProtocol.WorkIsReady }
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
  private val concurrencyController = if (mockChanceOfConflict >= 0 && mockChanceOfConflict <= 1) {
    log.info(f"Using a MockConcurrecyController with chance of conflict = ${mockChanceOfConflict * 100}%2.2f%%")
    new MockConcurrentConcurrencyController(mockChanceOfConflict)
  } else {
    log.debug("Using a normal concurrency controller (not a MockConcurrencyController).")
    new ConcurrentConcurrencyController(self, updateWorkers.toList, log)
  }

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
     * From the DistributedCoordinatorManager
     */
    case DistributedCoordinatorRegistrationProtocol.ListOfCoordinatorsWasUpdated(coordinators) =>
      coordinatorLocater.setCoordinators(coordinators.map(_._2))
      log.warning(s"Received updated list of coordinators: $coordinators")

    /**
     * From a Worker.
     *
     * Give him more work if we have some.
     */
    case ForemanWorkerProtocol.WorkerIsDoneAndRequestsWork(worker) =>
      log.debug(s"A worker finished his work: $worker")
      workerStats.stop(worker) // TODO extend these stats with the foremen
      workers.setIdle(worker)
      self ! ForemanWorkerProtocol.WorkerRequestsWork(worker)

    /**
     * From a Worker.
     *
     * Give him work if we have some.
     */
    case ForemanWorkerProtocol.WorkerRequestsWork(worker) =>
      // this test is needed because multiple WorkIsReady messages can be sent at once, 
      // which would lead to multiple WorkerRequestsWork messages afterwards
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
     * From a Foreman.
     *
     * Worker is alive. Add him to the list, watch him for
     * death, and let him know if there's work to be done
     */
    case ForemanCreated(foreman) =>
      log.warning(s"Foreman created: $foreman")
      context.watch(foreman)
      foremen += foreman
      notifyWorkers

    /**
     * From a Foreman.
     *
     * A worker wants more work.  If we know about him, he's not
     * currently doing anything, and we've got something to do,
     * give it to him.
     */
    case ForemanRequestsWork(foreman, nbRequests) =>
      log.debug(s"Foreman requests work: $foreman -> $nbRequests requests")
      // this test is needed because multiple WorkIsReady messages can be sent at once, 
      // which would lead to multiple WorkerRequestsWork messages afterwards
      if (foremen.idle.contains(foreman)) {
        // our prioritization between internal requests and external requests:
        // give internal requests priority in order to keep the total latency
        // of policy evaluations minimal
        if (!internalWorkQ.isEmpty) {
          val workBuffer = ListBuffer[(PolicyEvaluationRequest, ActorRef)]()
          for (i <- List.range(0, nbRequests)) {
            if (!internalWorkQ.isEmpty) {
              workBuffer += internalWorkQ.dequeue
            }
          }
          val work = workBuffer.toList
          foremen.foremanStartedWorkingOn(foreman, work)
          foreman ! CoordinatorForemanProtocol.WorkToBeDone(work)
          for ((request, client) <- work) {
            log.debug(s"[Evaluation ${request.id}] Sent request to foreman $foreman for evaluation")
          }
        } else if (!externalWorkQ.isEmpty) {
          val workBuffer = ListBuffer[(PolicyEvaluationRequest, ActorRef)]()
          for (i <- List.range(0, nbRequests)) {
            if (!externalWorkQ.isEmpty) {
              workBuffer += externalWorkQ.dequeue
            }
          }
          val work = workBuffer.toList
          foremen.foremanStartedWorkingOn(foreman, work)
          foreman ! CoordinatorForemanProtocol.WorkToBeDone(work)
          for ((request, client) <- work) {
            log.debug(s"[Evaluation ${request.id}] Sent request to foreman $foreman for evaluation")
          }
        }
      }

    /**
     * From a Foreman.
     *
     * Worker has completed its work and we can clear it out
     */
    case ForemanIsDoneAndRequestsWork(foreman, nbRequests) =>
      log.debug(s"Foreman is done and requests more work: $foreman")
      if (!foremen.contains(foreman)) {
        log.error(s"Blurgh! $foreman said it's done work but we didn't know about him")
      } else {
        foremen.foremanIsNowIdle(foreman)
        // send the worker some work
        self ! ForemanRequestsWork(foreman, nbRequests)
      }

    /**
     * For a coordinator managing (at least) the SUBJECT.
     *
     * A clients sends an authorization request.
     */
    case AuthorizationRequest(subjectId, actionId, resourceId, extraAttributes) =>
      inputStats.tick
      val client = sender
      // this is a request from a client => construct an id
      val id = constructNextId()
      Tracer.trace(id, s"Coordinator#$coordinatorId", "AuthorizationRequest") {
        if (mockDecision) {
          client ! AuthorizationDecision(id, Deny)
        } else {
          // store the client to forward the result later on
          clients(id) = client
          // construct the internal request
          val original = new PolicyEvaluationRequest(id, Top, subjectId, actionId, resourceId, extraAttributes)
          // store the original
          id2original(id) = original
          // In case of intelligent clients, we should only have received this request
          // if we should manage the subject of the request. In case of unintelligent 
          // clients, we could forward the request to the appropriate coordinator.
          coordinatorLocater.whatShouldIManage(self, original) match {
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
              coordinatorLocater.getCoordinatorForResource(resourceId) ! ManageResourceAndStartEvaluation(updated)
            case _ =>
              // we could forward here. For now: log an error
              log.error(s"[Evaluation ${id}] Received authorization request for which I am not responsible: ${AuthorizationRequest(subjectId, actionId, resourceId, extraAttributes)}")
          }
        }
      }

    /**
     * For a coordinator managing the RESOURCE.
     *
     * Another coordinator sends a request for us to manage the RESOURCE and evaluate
     * the given request.
     */
    case ManageResourceAndStartEvaluation(updatedRequest) =>
      Tracer.trace(updatedRequest.id, s"Coordinator#$coordinatorId", "") {
        // log
        log.debug(s"[Evaluation ${updatedRequest.id}] Queueing $updatedRequest via $sender (I should manage only the RESOURCE)")
        // add the attributes according to our own administration
        val updatedAgain = concurrencyController.startForResource(updatedRequest)
        // forward the request to one of our workers
        // note: the result should be sent to the other coordinator (i.e., the sender
        // of this StartRequestAndManageResource request)
        externalWorkQ.enqueue((updatedAgain, sender))
        notifyWorkers
      }

    /**
     * For a coordinator managing the RESOURCE.
     *
     * The commit of the evaluation failed at the coordinator that manages the subject
     * of the request and that coordinator has restarted the evaluation.
     */
    case ManageResourceAndRestartEvaluation(updatedRequest) =>
      Tracer.trace(updatedRequest.id, s"Coordinator#$coordinatorId", "") {
        // log
        log.debug(s"[Evaluation #${updatedRequest.id}] Queueing $updatedRequest via $sender (I should manage only the RESOURCE)")
        // add the attributes according to our own administration
        val updatedAgain = concurrencyController.restartForResource(updatedRequest)
        // forward the updated request to our foreman manager
        // note: the result should be sent to the other coordinator (i.e., the sender
        // of this StartRequestAndManageResource request)
        externalWorkQ.enqueue((updatedAgain, sender))
        notifyWorkers
      }

    /**
     * For a coordinator managing the SUBJECT.
     *
     * A worker has sent us the result of the policy evaluation that the other coordinator
     * started => try to commit it.
     */
    case result: PolicyEvaluationResult =>
      Tracer.trace(result.id, s"Coordinator#$coordinatorId", "PolicyEvaluationResult") {
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
        coordinatorLocater.whatShouldIManage(self, original) match {
          case BOTH =>
            // try to commit for both the subject and the resource
            if (concurrencyController.commitForBoth(result)) {
              log.debug(s"[Evaluation ${id}] The commit for request $id SUCCEEDED for BOTH")
              // the commit succeeded => remove the request from our administration and 
              // return the result to the client
              id2original.remove(id)
              val client = clients(id)
              client ! AuthorizationDecision(result.id, result.result.decision)
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
              coordinatorLocater.getCoordinatorForResource(original.resourceId) ! TryCommitForResource(filteredResult)
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
              coordinatorLocater.getCoordinatorForResource(original.resourceId) ! ManageResourceAndRestartEvaluation(updated)
            }
          case x =>
            // this should never happen
            throw new RuntimeException(s"I don't know what to do with this. Original = $original. I should manage: $x")
        }
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
      Tracer.trace(result.id, s"Coordinator#$coordinatorId", "TryCommitForResource") {
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
      Tracer.trace(result.id, s"Coordinator#$coordinatorId", "CommitForResourceSucceeded") {
        val id = result.id
        log.debug(s"[Evaluation ${id}] The other coordinator sent that the commit for request $id SUCCEEDED for the RESOURCE => wrap up")
        // 1. remove the request from our administration
        id2original.remove(id)
        // 2. finalize tentative attribute updates
        concurrencyController.finalizeCommitForSubject(result.id)
        // 3. send result to client
        val client = clients(id)
        client ! AuthorizationDecision(result.id, result.result.decision)
        clients.remove(id)
        outputStats.tick
      }

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
      Tracer.trace(result.id, s"Coordinator#$coordinatorId", "CommitForResourceFailed") {
        val id = result.id
        log.debug(s"[Evaluation ${id}] The other coordinator sent that the commit for request $id FAILED for the RESOURCE => restart")
        // the commit failed
        // 2. restart
        // The concurrency controller takes care of destroying tentative updates and
        // marking the evaluations that used these attributes as to be restarted.
        val original = id2original(id)
        val updated = concurrencyController.restartForSubject(original)
        // ask the other coordinator to restart as well. This coordinator will 
        // start the actual evaluation (but the result will be sent to us)
        coordinatorLocater.getCoordinatorForResource(original.resourceId) ! ManageResourceAndStartEvaluation(updated)
      }

    /**
     * An update worker has finished an update.
     */
    case UpdateFinished(updateWorker) =>
      // just forward this message to the concurrency controller (which is 
      // not an actor...)
      concurrencyController.updateFinished(updateWorker)

    /**
     * Someone requests a trace.
     */
    case TraceProtocol.GetTrace(evaluationId) =>
      val trace = Tracer.getTraceOfEvaluation(evaluationId)
      sender ! TraceProtocol.Trace(trace.steps.toList)

    /**
     * Just to be sure
     */
    case x =>
      log.warning(s"Unknown message received: $x")

  }

  log.debug(s"$self: I'm alive!")
}

///**
// * Class used for managing workers.
// *
// * This class is an update of ForemanAdministration to the image of WorkerManager.
// *
// * TODO: remove ForemanAdministration
// */
//class ForemanManager {
//
//  import scala.collection.mutable.Set
//
//  val foremen = Set.empty[ActorRef]
//  val busyForemen = Set.empty[ActorRef]
//  val idleForemen = Set.empty[ActorRef]
//
//  /**
//   * The total number of foremen
//   */
//  def size = foremen.size
//
//  /**
//   * Add a new foreman (as idle).
//   */
//  def +=(foreman: ActorRef) = {
//    foremen += foreman
//    idleForemen += foreman
//  }
//
//  /**
//   * Remove a foreman.
//   */
//  def -=(foreman: ActorRef) = {
//    foremen -= foreman
//    idleForemen -= foreman
//    busyForemen -= foreman
//  }
//
//  /**
//   * Return all idle workers.
//   */
//  def idle = idleForemen
//
//  /**
//   * Set the given foreman as busy.
//   */
//  def setBusy(foreman: ActorRef) {
//    idleForemen -= foreman
//    busyForemen += foreman
//  }
//
//  /**
//   * Set the given foreman as idle.
//   */
//  def setIdle(foreman: ActorRef) {
//    idleForemen += foreman
//    busyForemen -= foreman
//  }
//}