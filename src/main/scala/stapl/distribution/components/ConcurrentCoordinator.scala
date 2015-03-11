package stapl.distribution.components

import akka.actor.ActorRef
import akka.actor.ActorLogging
import akka.actor.Actor
import akka.actor.Terminated
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import akka.actor.Props
import scala.collection.mutable.{ Map, Queue }
import stapl.distribution.util.ThroughputStatistics
import scala.collection.mutable.ListBuffer
import stapl.distribution.components.CoordinatorForemanProtocol.PolicyEvaluationResult
import stapl.distribution.db.AttributeDatabaseConnection
import stapl.distribution.db.AttributeDatabaseConnectionPool
import stapl.core.ConcreteChangeAttributeObligationAction
import stapl.core.Attribute
import stapl.core.Update
import stapl.core.Append
import scala.collection.mutable.Set
import scala.collection.mutable.MultiMap
import scala.collection.mutable.HashMap
import stapl.core.ConcreteValue
import akka.event.LoggingAdapter
import stapl.distribution.db.HazelcastAttributeDatabaseConnection
import com.hazelcast.config.Config
import stapl.distribution.db.AttributeMapStore
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.IMap
import stapl.core.AttributeContainerType
import stapl.distribution.db.HazelcastAttributeDatabaseConnection
import com.hazelcast.config.MapConfig
import com.hazelcast.config.MapStoreConfig
import com.hazelcast.core.HazelcastInstance
import stapl.distribution.db.AttributeDatabaseConnectionPool
import com.hazelcast.core.ItemListener
import com.hazelcast.core.ItemEvent
import stapl.core.pdp.SimpleTimestampGenerator
import scala.util.Random
import scala.concurrent.duration._
import scala.util.{ Try, Success, Failure }
import stapl.core.ConcreteChangeAttributeObligationAction
import stapl.core.ConcreteObligationAction

/**
 * Class used for communication with foremen.
 */
class ForemanManager extends Actor with ActorLogging {

  import CoordinatorForemanProtocol._
  import InternalCoordinatorProtocol._

  /**
   *  Holds known workers and what they may be working on
   */
  private val administration = new ForemanAdministration

  /**
   * Holds the incoming list of work to be done as well
   * as the coordinator that asked for it
   */
  private val workQ = Queue.empty[(PolicyEvaluationRequest, ActorRef)]

  /**
   * Notifies foremen that there's work available, provided they're
   * not already working on something
   */
  def notifyForemen(): Unit = {
    if (!workQ.isEmpty) {
      // bootstrap the foremen that did not have work yet
      administration.idle.foreach { foreman =>
        foreman ! WorkIsReady
      }
    }
  }

  def receive = {

    /**
     * A coordinator sends us work to forward to the Foremen.
     */
    case Enqueue(request, theResultShouldGoTo) =>
      workQ.enqueue((request, theResultShouldGoTo))
      notifyForemen

    /**
     *  Worker is alive. Add him to the list, watch him for
     *  death, and let him know if there's work to be done
     */
    case ForemanCreated(foreman) =>
      log.debug(s"Foreman created: $foreman")
      context.watch(foreman)
      administration += foreman
      notifyForemen

    /**
     * 	A worker wants more work.  If we know about him, he's not
     *  currently doing anything, and we've got something to do,
     *  give it to him.
     */
    case ForemanRequestsWork(foreman, nbRequests) =>
      log.debug(s"Foreman requests work: $foreman -> $nbRequests requests")
      if (!workQ.isEmpty) {
        val workBuffer = ListBuffer[(PolicyEvaluationRequest, ActorRef)]()
        for (i <- List.range(0, nbRequests)) {
          if (!workQ.isEmpty) {
            val item = workQ.dequeue
            workBuffer += item
            log.debug(s"[Evaluation ${item._1.id}] Sending to foreman $foreman")
          }
        }
        val work = workBuffer.toList
        administration.foremanStartedWorkingOn(foreman, work)
        foreman ! WorkToBeDone(work)
        log.debug(s"Sent work to $foreman: $work")
      } else {
        log.debug(s"Sorry $foreman, we seem to be out of work.")
      }

    /**
     *  Worker has completed its work and we can clear it out
     */
    case ForemanIsDoneAndRequestsWork(foreman, nbRequests) =>
      log.debug(s"Foreman is done and requests more work: $foreman")
      if (!administration.contains(foreman)) {
        log.error(s"Blurgh! $foreman said it's done work but we didn't know about him")
      } else {
        administration.foremanIsNowIdle(foreman)
        // send the worker some work
        self ! ForemanRequestsWork(foreman, nbRequests)
      }

    /**
     *  A worker died.  If he was doing anything then we need
     *  to give it to someone else so we just add it back to the
     *  master and let things progress as usual
     */
    case Terminated(foreman) =>
      if (administration.contains(foreman) && administration.isBusy(foreman)) {
        log.error(s"Blurgh! $foreman died while processing ${administration.getWork(foreman)}")
        // Put the work that it was doing back in front of the queue
        val work = administration.getWork(foreman).get
        // (what we actually do: enqueue the work and cycle the work that 
        // was already in the queue)
        val enqueuedWork = workQ.size
        workQ.enqueue(work: _*)
        for (i <- List.range(0, enqueuedWork)) {
          // probably this is a very inefficient method of doing this, let's hope this does not
          // happen too often
          workQ.enqueue(workQ.dequeue)
        }
      }
      administration -= foreman
  }
}

/**
 * Enum for storing what entities we manage: the SUBJECT, the RESOURCE,
 * BOTH or NOTHING.
 */
sealed trait Managed extends Exception // extends Exception so that we can return it in Failure
case object ONLY_SUBJECT extends Managed
case object ONLY_RESOURCE extends Managed
case object BOTH extends Managed
case object NOTHING extends Managed

/**
 * Class used for concurrency control.
 *
 * Strategy: for each ongoing request we maintain the relevant attributes
 * that have been updated and at commit time, we check whether the evaluation
 * read an attribute that has been updated by another evaluation while ongoing.
 * Although it can be that the ongoing evaluation read the new value of the
 * attribute, we work conservatively for now and fail the commit in case of
 * *possible* conflict without checking the value of the employed attributes.
 *
 * Notice that we only maintain the *relevant* attributes of a policy evaluation.
 * These are the attributes that have been written by another evaluation while ongoing
 * and apply to the subject or the resource of the evaluation.
 */
class ConcurrentConcurrencyController(coordinator: ActorRef, updateWorkers: List[ActorRef], log: LoggingAdapter) {

  import scala.collection.mutable.Map

  /**
   * Map of id->request for efficient search.
   */
  private val id2request = Map[String, PolicyEvaluationRequest]()

  /**
   * Efficient mapping of subjectId and resourceId to all ongoing
   * policy evaluations for these ids.
   */
  private val subjectId2Evaluation = new HashMap[String, Set[PolicyEvaluationRequest]] with MultiMap[String, PolicyEvaluationRequest]
  private val resourceId2Evaluation = new HashMap[String, Set[PolicyEvaluationRequest]] with MultiMap[String, PolicyEvaluationRequest]

  /**
   * The lists of attributes that have been updated during a certain policy evaluation.
   * 
   * Note: we only store attributes that can potentially be conflicting and for which we are
   * responsible. So if we only manage the SUBJECT of an evaluation, the list of attribute updates
   * will only contain updates that concern this SUBJECT.
   *
   * TODO probably, the best idea for the tentative updates is to store a state here: FINAL or TENTATIVE?
   */
  private val updatesWhileEvaluating = Map[String, ListBuffer[Attribute]]()

  /**
   * The queues of work of the UpdateWorkers.
   */
  private val ongoingUpdates = Map[ActorRef, Queue[ConcreteChangeAttributeObligationAction]]()
  updateWorkers foreach { ongoingUpdates(_) = Queue() }
  // we also keep an index of (entityId,attribute) -> (actorRef,nbOngoingUpdatesForThisAttribute)
  // for efficient search of the correct UpdateWorker to assign an update to
  private val update2worker = Map[(String, Attribute), (ActorRef, Int)]()

  /**
   * The ongoing updates per entityId.
   */
  private val subjectId2OngoingUpdates = new HashMap[String, Map[Attribute, ConcreteValue]]
  private val resourceId2OngoingUpdates = new HashMap[String, Map[Attribute, ConcreteValue]]

  /**
   * Indicates to the controller that the evaluation of the given
   * request is going to start and that this controller should do
   * the administration for the SUBJECT and the RESOURCE of the request.
   *
   * This method adds the original request with the appropriate
   * attributes added. These attributes are attributes that are
   * currently being updated as the result of previous policy evaluations
   * but are not sure to the committed in the database yet.
   */
  def startForBoth(request: PolicyEvaluationRequest): PolicyEvaluationRequest = {
    // set up the administration
    id2request(request.id) = request
    subjectId2Evaluation.addBinding(request.subjectId, request)
    resourceId2Evaluation.addBinding(request.resourceId, request)
    updatesWhileEvaluating(request.id) = ListBuffer()

    // add suitable attributes of ongoing attribute updates
    addSuitableAttributes(request)
  }

  /**
   * Indicates to the controller that the evaluation of the given
   * request is going to start and that this controller should do
   * the administration for the subject of the request.
   *
   * This method adds the original request with the appropriate
   * attributes added. These attributes are attributes that are
   * currently being updated as the result of previous policy evaluations
   * but are not sure to the committed in the database yet.
   */
  def startForSubject(request: PolicyEvaluationRequest): PolicyEvaluationRequest = {
    // set up the administration
    id2request(request.id) = request
    subjectId2Evaluation.addBinding(request.subjectId, request)
    updatesWhileEvaluating(request.id) = ListBuffer()

    // add suitable attributes of ongoing attribute updates
    addSuitableAttributes(request)
  }

  /**
   * Indicates to the controller that the evaluation of the given
   * request is going to start and that this controller should do
   * the administration for the subject of the request.
   *
   * This method adds the original request with the appropriate
   * attributes added. These attributes are attributes that are
   * currently being updated as the result of previous policy evaluations
   * but are not sure to bhe committed in the database yet.
   */
  def startForResource(request: PolicyEvaluationRequest): PolicyEvaluationRequest = {
    // set up the administration
    id2request(request.id) = request
    resourceId2Evaluation.addBinding(request.resourceId, request)
    updatesWhileEvaluating(request.id) = ListBuffer()

    // add suitable attributes of ongoing attribute updates
    addSuitableAttributes(request)
  }

  /**
   * Note: if we manage both the subject and the resource of this request,
   * this method will have added the necessary attributes for both.
   */
  private def addSuitableAttributes(request: PolicyEvaluationRequest): PolicyEvaluationRequest = {
    var attributes = request.extraAttributes
    // Note: subjectId2OngoingUpdates will only contain this request if
    // we should also manage this request => this method can be used from
    // both the subject-specific and resource-specific methods.
    // Note: there will never be tentative updates for resource attributes
    if (subjectId2OngoingUpdates.contains(request.subjectId)) {
      val toAdd = subjectId2OngoingUpdates(request.subjectId).toSeq
      attributes ++= toAdd
      log.debug(s"[Evaluation ${request.id}] Added the following ongoing attributes because of ongoing updates on the SUBJECT: $toAdd")
    }
    if (resourceId2OngoingUpdates.contains(request.resourceId)) {
      val toAdd = resourceId2OngoingUpdates(request.resourceId).toSeq
      attributes ++= toAdd
      log.debug(s"[Evaluation ${request.id}] Added the following ongoing attributes because of ongoing updates on the RESOURCE: $toAdd")
    }
    request.copy(extraAttributes = attributes)
  }

  /**
   * Called by a coordinator that manages BOTH the resource and the subject.
   *
   * Tries to commit the evaluation of which the result is given and
   * returns whether the commit has succeeded or not. This commit will
   * succeed if there are no conflicting attribute reads and/or writes.
   *
   * This method also updates the internal state according to the success
   * or failure of the commit (in the latter case: remove the evaluation from
   * the administration).
   *
   * If this method returns true, the attribute updates of the evaluation
   * are not yet processed but *will* be processed and will be processed
   * *correctly* so that you can assume that the evaluation is committed.
   */
  def commitForBoth(result: PolicyEvaluationResult): Boolean = {
    val id = result.id
    // check that we *can* commit: check for read-write conflicts
    if (canCommit(result)) {
      // we can commit => execute the obligations and update the concurrency control administration
      val request = id2request(id)
      result.result.obligationActions foreach {
        _ match {
          case change: ConcreteChangeAttributeObligationAction =>
            execute(change)
            // also store the updates for ongoing evaluations with which
            // these can conflict. We should only store these for evaluations
            // that share the entityId
            change.attribute.cType match {
              case stapl.core.SUBJECT =>
                subjectId2Evaluation(request.subjectId) foreach { x =>
                  updatesWhileEvaluating(x.id) += change.attribute
                  log.debug(s"[Evaluation ${id}] Stored the possibly conflicting attribute update for the SUJBECT with evaluation ${x.id}")
                }
              case stapl.core.RESOURCE =>
                resourceId2Evaluation(request.resourceId) foreach { x =>
                  updatesWhileEvaluating(x.id) += change.attribute
                  log.debug(s"[Evaluation ${id}] Stored the possibly conflicting attribute update for the RESOURCE with evaluation ${x.id}")
                }
              case x => throw new IllegalArgumentException(s"For now, you can only update subject and resource attributes. Given container type: $x")
            }
          case x => log.debug(s"For now, we can only process attribute changes. Given ObligationAction: $x")
        }
      }
      // remove the evaluation from the administration
      cleanUp(id)
      // return
      true
    } else {
      // Nope => return false
      cleanUp(id)
      return false
    }
  }

  /**
   * Called by a coordinator that manages only the SUBJECT. The attribute
   * updates in this result should be executed TENTATIVELY.
   */
  def commitForSubject(result: PolicyEvaluationResult): Boolean = {
    val id = result.id
    if (canCommit(result)) {
      // execute tentative updates for subject attributes
      val request = id2request(id)
      result.result.obligationActions foreach {
        _ match {
          case change: ConcreteChangeAttributeObligationAction =>
            // also store the updates for ongoing evaluations with which
            // these can conflict. We should only store these for the SUBJECT
            change.attribute.cType match {
              case stapl.core.SUBJECT =>
                subjectId2Evaluation(request.subjectId) foreach { x =>
                  // updatesWhileEvaluating(x.id) += change.attribute
                  // TODO store the attribute update tentatively here
                  log.debug(s"[Evaluation ${id}] Stored the possibly conflicting attribute update TENTATIVELY for the SUBJECT with evaluation ${x.id}")
                }
              case stapl.core.RESOURCE =>
              	// Nothing to do, this attribute will be handled by the other coordinator
              case x => throw new IllegalArgumentException(s"For now, you can only update subject and resource attributes. Given container type: $x")
            }
          case x => log.debug(s"[Evaluation ${id}] For now, we can only process attribute changes. Given ObligationAction: $x")
        }
      }
      true
    } else {
      cleanUp(id)
      return false
    }
  }

  /**
   * Called by a coordinator that manages only the RESOURCE. The attribute
   * updates in this result can be executed IMMEDIATELY.
   */
  def commitForResource(result: PolicyEvaluationResult): Boolean = {
    val id = result.id
    if (canCommit(result)) {
      // the other coordinator has tentatively committed the attribute updates
      // of the subject, we can immediately commit the attribute updates of the resource
      // => execute the obligations and update the concurrency control administration
      val request = id2request(id)
      result.result.obligationActions foreach {
        _ match {
          case change: ConcreteChangeAttributeObligationAction =>
            // also store the updates for ongoing evaluations with which
            // these can conflict. We should only store these for the RESOURCE
            change.attribute.cType match {
              case stapl.core.SUBJECT =>
              	throw new IllegalArgumentException(s"The other coordinator should not forward SUBJECT attribute updates, but received one: $change")
              case stapl.core.RESOURCE =>
                execute(change)
                resourceId2Evaluation(request.resourceId) foreach { x =>
                  updatesWhileEvaluating(x.id) += change.attribute
                  log.debug(s"[Evaluation ${id}] Stored the possibly conflicting attribute update for the RESOURCE with evaluation ${x.id}")
                }
              case x => throw new IllegalArgumentException(s"For now, you can only update subject and resource attributes. Given container type: $x")
            }
          case x => log.debug(s"For now, we can only process attribute changes. Given ObligationAction: $x")
        }
      }
      // remove the evaluation from the administration
      cleanUp(id)
      // return
      true
    } else {
      cleanUp(id)
      return false
    }
  }

  /**
   * This method is called by the coordinator managing the SUBJECT
   * when the other coordinator has indicated that it is ok to commit.
   */
  def finalizeCommitForSubject(requestId: String) {
    // TODO finalize tentative updates
  }

  /**
   * This method is called by the coordinator managing the SUBJECT
   * when the other coordinator has indicated that he failed to commit for the
   * resource, so we should restart the evaluation. This method resets the
   * list of updates while evaluating the request and returns the given request
   * with the appropriate attributes added (see startFor...()).
   */
  def restartForSubject(request: PolicyEvaluationRequest): PolicyEvaluationRequest = {
    // clean up first
    cleanUp(request.id)

    // set up the administration again, add appropriate attributes and return
    startForSubject(request)
  }

  /**
   * Indicates to the controller that the evaluation of the given
   * request is going to restart and that this controller should do
   * the administration for the subject of the request. First, we clean
   * up the administration that we already had for this request.
   *
   * This method adds the original request with the appropriate
   * attributes added. These attributes are attributes that are
   * currently being updated as the result of previous policy evaluations
   * but are not sure to be committed in the database yet.
   */
  def restartForResource(request: PolicyEvaluationRequest): PolicyEvaluationRequest = {
    // this is a restarted evaluation => clean up first
    cleanUp(request.id)

    // set up the administration again, add appropriate attributes and return
    startForResource(request)
  }

  /**
   * Helper function to check whether we can commit a certain result.
   *
   * Note: this method can be called from methods for BOTH, for the SUBJECT
   * and for the RESOURCE because this method takes into account our overall
   * administration and this administration will not contain data about the
   * subject/resource if we do not manage it.
   *
   * Note: this also means that this method should be called for BOTH if
   * we manage both the resource and the subject!
   */
  private def canCommit(result: PolicyEvaluationResult): Boolean = {
    val id = result.id
    // 1. check whether this policy evaluation should be restarted 
    //		because of failed tentative updates
    // TODO    
    // 2. check if we *can* commit: check for read-write conflicts
    val possibleConflicts = updatesWhileEvaluating(id)
    result.result.employedAttributes foreach {
      case (attribute, value) =>
        if (possibleConflicts.contains(attribute)) {
          // we found a conflict => return false so that the evaluation is restarted,
          // but reset the list of updates while evaluation so that it can finish 
          // correctly the next time
          updatesWhileEvaluating(id) = ListBuffer()
          log.debug(s"[Evaluation ${result.id}] Found a conflicting attribute update for $attribute")
          return false
        }
    }
    true
  }

  /**
   * Helper method to clean up all administration for a certain evaluation.
   */
  private def cleanUp(id: String) {
    val request = id2request(id)
    subjectId2Evaluation.removeBinding(request.subjectId, request)
    resourceId2Evaluation.removeBinding(request.resourceId, request)
    id2request.remove(id)
    updatesWhileEvaluating.remove(id)
  }

  /**
   * Executes a single attribute update by assigning it to the
   * appropriate UpdateWorker. An update of the same attribute
   * of the same entity should be handled sequentially, so by the
   * same UpdateWorker. So: check whether another update for the
   * given entityId and attribute is currently assigned to a worker
   * and if so, assign this update to the same worker. If not: assign
   * the update to the worker with the least work.
   */
  private def execute(update: ConcreteChangeAttributeObligationAction): Unit = {
    // store that the update is going on
    // Note: this can replace a previous ongoing update, but the fact
    // that the new update is assigned to the same update worker as
    // the previous update guarantees serial execution and that the second
    // update will be the final one
    val target = update.attribute.cType match {
      case stapl.core.SUBJECT => subjectId2OngoingUpdates
      case stapl.core.RESOURCE => resourceId2OngoingUpdates
      case x => throw new IllegalArgumentException(s"You can only update SUBJECT or RESOURCE attributes. Given attribute: $x")
    }
    if (target.contains(update.entityId)) {
      target(update.entityId)(update.attribute) = update.value
    } else {
      target(update.entityId) = Map(update.attribute -> update.value)
    }
    // send the update to a worker
    val key = (update.entityId, update.attribute)
    if (update2worker.contains(key)) {
      val (worker, count) = update2worker(key)
      update2worker(key) = (worker, count + 1)
      ongoingUpdates(worker).enqueue(update)
      worker ! update
    } else {
      // assign to the UpdateWorker with the least work
      var min = ongoingUpdates.head._2.size
      var winner = ongoingUpdates.head._1
      ongoingUpdates map {
        case (worker, queue) =>
          val size = queue.size
          if (size < min) {
            min = size
            winner = worker
          }
      }
      update2worker(key) = (winner, 1)
      ongoingUpdates(winner).enqueue(update)
      winner ! update
    }
  }

  /**
   * Indicates to the controller that an UpdateWorker has finished.
   */
  def updateFinished(updateWorker: ActorRef): Unit = {
    // remove the head, this is always the update that is finished
    val finishedUpdate = ongoingUpdates(updateWorker).dequeue
    // also decrement the number of ongoing attribute updates per worker
    val key = (finishedUpdate.entityId, finishedUpdate.attribute)
    val (worker, count) = update2worker(key)
    if (count == 1) {
      // this was the last one
      update2worker.remove(key)
    } else {
      update2worker(key) = (worker, count - 1)
    }
    // also remove the ongoing update from the list of ongoing updates
    // per attribute IF this has not been overwritten in the meanwhile
    val target = finishedUpdate.attribute.cType match {
      case stapl.core.SUBJECT => subjectId2OngoingUpdates
      case stapl.core.RESOURCE => resourceId2OngoingUpdates
      // no need to check the other cases, this has been checked when adding
      // to ongoingUpdates
    }
    if (target.contains(finishedUpdate.entityId)) {
      val values = target(finishedUpdate.entityId)
      if (values.contains(finishedUpdate.attribute) && values(finishedUpdate.attribute) == finishedUpdate.value) {
        values.remove(finishedUpdate.attribute)
      }
      if (values.isEmpty) {
        target.remove(finishedUpdate.entityId)
      }
    }
  }
}

/**
 * 
 */
class ConcurrentCoordinator(coordinatorId: Long, pool: AttributeDatabaseConnectionPool, nbUpdateWorkers: Int,
  coordinatorManager: CoordinatorLocater, foremanManager: ActorRef) extends Actor with ActorLogging {

  import ClientCoordinatorProtocol._
  import ConcurrentCoordinatorProtocol._
  import InternalCoordinatorProtocol._

  /**
   * Holds the mapping between the clients and the authorization requests
   * they sent.
   */
  private val clients: Map[String, ActorRef] = Map()

  /**
   * Some statistics of the throughput
   */
  //private val stats = new ThroughputStatistics
  private val stats = new ThroughputStatistics("Coordinator", 1000, 10, false) // disabled

  /**
   * A timestamp generator for generating ids for the evaluations.
   */
  private val timestampGenerator = new SimpleTimestampGenerator

  def constructNextId() = s"$coordinatorId:${timestampGenerator.getTimestamp}"

  /**
   * Construct our UpdateWorkers for the ConcurrencyController.
   */
  private val updateWorkers = scala.collection.mutable.ListBuffer[ActorRef]()
  // private val db = AttributeDatabaseConnectionPool("localhost", 3306, "stapl-attributes", "root", "root", false /* we want to write => NOT readonly */)
  1 to nbUpdateWorkers foreach { _ =>
    log.debug("Setting up another update worker")
    updateWorkers += context.actorOf(Props(classOf[UpdateWorker], self, pool.getConnection))
    log.debug("Setting up another update worker")
  }
  private val concurrencyController = new ConcurrentConcurrencyController(self, updateWorkers.toList, log)

  /**
   * A mapping of id -> original request for restarting requests efficiently.
   */
  private val id2original = scala.collection.mutable.Map[String, PolicyEvaluationRequest]()

  def receive = {

    /**
     * For a coordinator managing (at least) the SUBJECT.
     *
     * A clients sends an authorization request.
     */
    case AuthorizationRequest(subjectId, actionId, resourceId, extraAttributes) =>
      val client = sender
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
          log.debug(s"[Evaluation ${id}] Received authorization request: ($subjectId, $actionId, $resourceId) from $client. (I should manage both => queuing it immediately)")
          // add the attributes according to our administration
          val updated = concurrencyController.startForBoth(original)
          // forward the updated request to our foreman manager 
          foremanManager ! Enqueue(updated, self)
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

    /**
     * For a coordinator managing the RESOURCE.
     *
     * Another coordinator sends a request for us to manage the RESOURCE and evaluate
     * the given request.
     */
    case ManageResourceAndStartEvaluation(updatedRequest) =>
      // log
      log.debug(s"[Evaluation #${updatedRequest.id}] Queueing $updatedRequest via $sender (I should manage only the RESOURCE)")
      // add the attributes according to our own administration
      val updatedAgain = concurrencyController.startForResource(updatedRequest)
      // forward the updated request to our foreman manager
      // note: the result should be sent to the other coordinator (i.e., the sender
      // of this StartRequestAndManageResource request)
      foremanManager ! Enqueue(updatedAgain, sender)

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
      foremanManager ! Enqueue(updatedAgain, sender)

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
            stats.tick
          } else {
            log.debug(s"[Evaluation ${id}] The commit for request $id FAILED for BOTH")
            // the commit failed => restart the evaluation (add the necessary
            // attributes to the original again)
            log.warning(s"The commit for request $id FAILED for BOTH, restarting it")
            // note: the concurrency controller will have already cleaned up its state
            // in the commitForBoth() method
            val updated = concurrencyController.startForBoth(original)
            foremanManager ! Enqueue(updated, self)
          }
        case ONLY_SUBJECT =>
          // try to commit for the subject
          if (concurrencyController.commitForSubject(result)) {
            log.debug(s"[Evaluation ${id}] The commit for request $id SUCCEEDED for the SUBJECT")
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
            // in the commitForBoth() method
            val original = id2original(id)
            val updated = concurrencyController.startForSubject(original)
            // ask the other coordinator to restart as well. This coordinator will 
            // start the actual evaluation (but the result will be sent to us).
            // This coordinator also already contain
            coordinatorManager.getCoordinatorForResource(original.resourceId) ! ManageResourceAndStartEvaluation(updated)
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
      stats.tick

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
      coordinatorManager.getCoordinatorForResource(original.resourceId) ! ManageResourceAndRestartEvaluation(updated)

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