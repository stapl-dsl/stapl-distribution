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
import stapl.core.ConcreteChangeAttributeObligationAction
import stapl.core.ConcreteChangeAttributeObligationAction
import stapl.core.Update
import stapl.core.Append
import scala.collection.mutable.Set
import scala.collection.mutable.MultiMap
import scala.collection.mutable.HashMap
import stapl.core.ConcreteChangeAttributeObligationAction
import stapl.core.ConcreteValue
import stapl.core.SUBJECT
import stapl.core.RESOURCE
import akka.event.LoggingAdapter
import stapl.core.ConcreteChangeAttributeObligationAction
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

/**
 * Class used for temporarily storing the clients that sent an
 * authorization request so that we can pass the decision back
 * to the client later on.
 */
class ClientAuthorizationRequestManager() {

  import scala.collection.mutable.Map

  private var counter = 0

  private val clients: Map[String, ActorRef] = Map()

  /**
   * Stores the actor in the cache and returns the generated
   * id of its authorization request.
   */
  def store(actor: ActorRef) = {
    val id = s"$counter"
    clients(id) = actor
    counter += 1
    id
  }

  /**
   * Returns the actor that sent the authorization request with the given id
   * and removes this actor from the map.
   */
  def get(id: String) = {
    val result = clients(id)
    clients.remove(id)
    result
  }
}

/**
 * Class used for managing foremen.
 */
class ForemanAdministration {

  val foremen = Map.empty[ActorRef, Option[List[(PolicyEvaluationRequest, ActorRef)]]]

  /**
   * Add the given Foreman to the list of Foremen (as idle).
   */
  def +=(foreman: ActorRef) = foremen(foreman) = None

  /**
   * Remove the given Foremen from the list of Foremen.
   */
  def -=(foreman: ActorRef) = foremen -= foreman

  /**
   * Returns whether the given foreman is managed by this manager.
   */
  def contains(foreman: ActorRef) = foremen.contains(foreman)

  /**
   * Returns the idle foremen.
   */
  def idle = foremen.filter(_._2.isEmpty).keys

  /**
   * Returns whether the given foremen is idle.
   */
  def isIdle(foreman: ActorRef) = foremen(foreman) == None

  /**
   * Returns whether the given foreman is busy.
   */
  def isBusy(foreman: ActorRef) = !isIdle(foreman)

  /**
   * Set the given foreman as busy on the given work.
   */
  def foremanStartedWorkingOn(foreman: ActorRef, work: List[(PolicyEvaluationRequest, ActorRef)]) =
    // FIXME not correct, this should be an append if the foreman already has work
    foremen(foreman) = Some(work)

  /**
   * Set the given foreman as idle.
   */
  def foremanIsNowIdle(foreman: ActorRef) = foremen(foreman) = None

  /**
   * Returns the work currently assigned to the given foreman.
   */
  def getWork(foreman: ActorRef) = foremen(foreman)
}

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
 * and apply to either the subject or the resource of this evaluation.
 */
class ConcurrencyController(coordinator: ActorRef, updateWorkers: List[ActorRef], log: LoggingAdapter) {

  import scala.collection.mutable.Map

  /**
   * Map of id->request for efficient search.
   */
  private val ongoingEvaluations = Map[String, PolicyEvaluationRequest]()

  /**
   * Efficient mapping of subjectId and resourceId to all ongoing
   * policy evaluations for these ids.
   */
  private val subjectId2Evaluation = new HashMap[String, Set[PolicyEvaluationRequest]] with MultiMap[String, PolicyEvaluationRequest]
  private val resourceId2Evaluation = new HashMap[String, Set[PolicyEvaluationRequest]] with MultiMap[String, PolicyEvaluationRequest]

  /**
   * The lists of attributes that have been updated during a certain policy evaluation.
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
   * request is going to start.
   *
   * This method returns the original request with the appropriate
   * attributes added. These attributes are attributes that are
   * currently being updated as the result of previous policy evaluations
   * but are not sure to bhe committed in the database yet.
   */
  def start(request: PolicyEvaluationRequest): PolicyEvaluationRequest = {
    // set up the administration
    ongoingEvaluations(request.id) = request
    subjectId2Evaluation.addBinding(request.subjectId, request)
    resourceId2Evaluation.addBinding(request.resourceId, request)
    updatesWhileEvaluating(request.id) = ListBuffer()
    // add suitable attributes of ongoing attribute updates
    addSuitableAttributes(request)
  }

  /**
   * Indicates to the controller that the evaluation of the given request
   * is going to restart. This resets the list of updates while evaluating
   * the request and returns the given request with the appropriate
   * attributes added (see start()).
   */
  def restart(request: PolicyEvaluationRequest): PolicyEvaluationRequest = {
    // reset the administration
    updatesWhileEvaluating(request.id) = ListBuffer()
    // add suitable attributes of ongoing attribute updates
    addSuitableAttributes(request)
  }

  private def addSuitableAttributes(request: PolicyEvaluationRequest): PolicyEvaluationRequest = {
    var attributes = request.extraAttributes
    if (subjectId2OngoingUpdates.contains(request.subjectId)) {
      val toAdd = subjectId2OngoingUpdates(request.subjectId).toSeq
      attributes ++= toAdd
      log.debug(s"Added the following attributes because of ongoing updates on the SUBJECT: $toAdd")
    }
    if (resourceId2OngoingUpdates.contains(request.resourceId)) {
      val toAdd = resourceId2OngoingUpdates(request.resourceId).toSeq
      attributes ++= toAdd
      log.debug(s"Added the following attributes because of ongoing updates on the RESOURCE: $toAdd")
    }
    PolicyEvaluationRequest(request.id, request.policy, request.subjectId, request.actionId, request.resourceId, attributes)
  }

  /**
   * Tries to commit the evaluation of which the result is given and
   * returns whether the commit has succeeded or not. This commit will
   * succeed if there are no conflicting attribute reads and/or writes.
   * If this method returns true, the attribute updates of the evaluation
   * are not yet processed but *will* be processed and will be processed
   * *correctly* so that you can assume that the evaluation is committed.
   */
  def commit(result: PolicyEvaluationResult): Boolean = {
    val id = result.id
    // 1. check if we *can* commit: check for read-write conflicts
    val possibleConflicts = updatesWhileEvaluating(id)
    result.result.employedAttributes foreach {
      case (attribute, value) =>
        if (possibleConflicts.contains(attribute)) {
          // we found a conflict => return false so that the evaluation is restarted,
          // but reset the list of updates while evaluation so that it can finish 
          // correctly the next time
          updatesWhileEvaluating(id) = ListBuffer()
          return false
        }
    }
    // 2. we can commit => execute the obligations and update the concurrency
    //						control administration
    val request = ongoingEvaluations(id)
    result.result.obligationActions foreach {
      _ match {
        case change: ConcreteChangeAttributeObligationAction =>
          execute(change)
          // also store the updates for ongoing evaluations with which
          // these can conflict. We should only store these for evaluations
          // that share the entityId
          // Note: since we only store the conflicting attribute and not the 
          // entity id, we should only store attribute updates in
          // updatesWhileEvaluating() that belong to the subject/resource
          // in question. Otherwise, imaging that you subject1 makes two requests:
          // one to access resource1 and one to access resource2 and the policy
          // updates the resource.history attribute. Then this method would store
          // that "resource.history was changed during evaluation1" and evaluation2
          // would fail because it read resource.history, while it read this attribute
          // of *another* resource. 
          // TODO rethink this over, is this correct?
          change.attribute.cType match {
            case stapl.core.SUBJECT =>
              subjectId2Evaluation(request.subjectId) foreach { x =>
                updatesWhileEvaluating(x.id) += change.attribute
                log.debug(s"Stored the possibly conflicting attribute update for the SUJBECT with evaluation ${x.id}")
              }
            case stapl.core.RESOURCE =>
              resourceId2Evaluation(request.resourceId) foreach { x =>
                updatesWhileEvaluating(x.id) += change.attribute
                log.debug(s"Stored the possibly conflicting attribute update for the RESOURCE with evaluation ${x.id}")
              }
            case x => throw new IllegalArgumentException(s"For now, you can only update subject and resource attributes. Given container type: $x")
          }
        case x => throw new IllegalArgumentException(s"For now, we can only process attribute changes. Given ObligationAction: $x")
      }
    }
    // remove the evaluation from the administration
    subjectId2Evaluation.removeBinding(request.subjectId, request)
    resourceId2Evaluation.removeBinding(request.resourceId, request)
    ongoingEvaluations.remove(id)
    updatesWhileEvaluating.remove(id)
    // return
    true
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
 * For disabling concurrency control.
 */
class MockConcurrencyController(coordinator: ActorRef, updateWorkers: List[ActorRef], log: LoggingAdapter)
  extends ConcurrencyController(null, List(), null) {

  updateWorkers foreach { _ ! "terminate" }

  override def start(request: PolicyEvaluationRequest): PolicyEvaluationRequest = {
    // do nothing
    request
  }

  override def commit(result: PolicyEvaluationResult): Boolean = {
    // do nothing
    true
  }

  override def updateFinished(updateWorker: ActorRef): Unit = {
    // do nothing
  }
}

/**
 * Actor used for processing attribute updates asynchronously from the
 * coordinator. Every message sent to this UpdateWorker is handled sequentially
 * and blocking, so be sure to assign UpdateWorkers to separate threads.
 */
class UpdateWorker(coordinator: ActorRef, db: AttributeDatabaseConnection) extends Actor with ActorLogging {

  /**
   * Note: we use the ObligationActions as messages.
   */
  def receive = {

    /**
     * Update an attribute value.
     */
    case update: ConcreteChangeAttributeObligationAction =>
      log.debug(s"Starting attribute update: $update")
      val ConcreteChangeAttributeObligationAction(entityId, attribute, value, changeType) = update
      changeType match {
        case Update => db.updateAnyAttribute(entityId, attribute, value.representation)
        case Append => db.storeAnyAttribute(entityId, attribute, value.representation)
      }
      coordinator ! UpdateFinished(self)
      log.debug(s"Finished attribute update: $update")

    case "terminate" => context.stop(self)

    case x => log.warning(s"Unknown message receiced: $x")
  }

}

/**
 * For communication between the UpdateWorkers and the Coordinator.
 */
case class UpdateFinished(updateWorker: ActorRef)

/**
 * Class used for representing the Coordinator that manages all foremen
 * and ensures correct concurrency.
 *
 * TODO: the work mgmt is not correct yet: the list of requests assigned
 * to a foremen is not extended with newly assigned work and is also not cleared
 * if the foreman has finished certain jobs except if he sends a "Finished" message
 * (so not in the "give me more" message)
 */
class Coordinator(pool: AttributeDatabaseConnectionPool, nbUpdateWorkers: Int, disableConcurrencyControl: Boolean = false) extends Actor with ActorLogging {

  import ClientCoordinatorProtocol._
  import CoordinatorForemanProtocol._

  /**
   *  Holds known workers and what they may be working on
   */
  private val foremen = new ForemanAdministration

  /**
   * Holds the incoming list of work to be done as well
   * as the memory of who asked for it
   */
  private val workQ = Queue.empty[(PolicyEvaluationRequest, ActorRef)]

  /**
   * Holds the mapping between the clients and the authorization requests
   * they sent.
   */
  private val clients = new ClientAuthorizationRequestManager

  /**
   * Some statistics of the throughput
   */
  private val stats = new ThroughputStatistics

  /**
   * Construct our UpdateWorkers for the ConcurrencyController.
   */
  private val updateWorkers = scala.collection.mutable.ListBuffer[ActorRef]()
  // private val db = AttributeDatabaseConnectionPool("localhost", 3306, "stapl-attributes", "root", "root", false /* we want to write => NOT readonly */)
  1 to nbUpdateWorkers foreach { _ =>
    updateWorkers += context.actorOf(Props(classOf[UpdateWorker], self, pool.getConnection))
  }
  private val concurrencyController = disableConcurrencyControl match {
    case false =>
      new ConcurrencyController(self, updateWorkers.toList, log)
    case true =>
      log.debug("Disabled concurrency control")
      new MockConcurrencyController(self, updateWorkers.toList, log)
  }
  //  private val concurrencyController = new MockConcurrencyController(self, updateWorkers.toList)

  /**
   * A mapping of id->request for restarting requests efficiently.
   */
  private val id2request = scala.collection.mutable.Map[String, PolicyEvaluationRequest]()

  /**
   * Notifies foremen that there's work available, provided they're
   * not already working on something
   */
  def notifyForemen(): Unit = {
    if (!workQ.isEmpty) {
      // bootstrap the foremen that did not have work yet
      foremen.idle.foreach { foreman =>
        foreman ! WorkIsReady
      }
    }
  }

  def receive = {
    /**
     *  Worker is alive. Add him to the list, watch him for
     *  death, and let him know if there's work to be done
     */
    case ForemanCreated(foreman) =>
      log.debug(s"Foreman created: $foreman")
      context.watch(foreman)
      foremen += foreman
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
            workBuffer += workQ.dequeue
          }
        }
        val work = workBuffer.toList
        foremen.foremanStartedWorkingOn(foreman, work)
        foreman ! WorkToBeDone(work)
        log.debug(s"Sent work to $foreman: $work")
      }

    /**
     *  Worker has completed its work and we can clear it out
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
     *  A worker died. If he was doing anything then we need
     *  to give it to someone else so we just add it back to the
     *  master and let things progress as usual
     */
    case Terminated(foreman) =>
      if (foremen.contains(foreman) && foremen.isBusy(foreman)) {
        log.error(s"Blurgh! $foreman died while processing ${foremen.getWork(foreman)}")
        // Put the work that it was doing back in front of the queue
        val work = foremen.getWork(foreman).get
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
      foremen -= foreman

    /**
     *
     */
    case AuthorizationRequest(subjectId, actionId, resourceId, extraAttributes) =>
      log.debug(s"Queueing ($subjectId, $actionId, $resourceId, $extraAttributes) from $sender")
      val id = clients.store(sender)
      val original = new PolicyEvaluationRequest(id, Top, subjectId, actionId, resourceId, extraAttributes)
      id2request(id) = original
      val updated = concurrencyController.start(original)
      workQ.enqueue((updated, self))
      notifyForemen

    /**
     *
     */
    case result: PolicyEvaluationResult =>
      val id = result.id
      log.debug(s"Received authorization decision: ($id, $result)")
      if (concurrencyController.commit(result)) {
        log.debug(s"The commit for request $id succeeded")
        // the commit succeeded => remove the request from our administration and 
        // return the result to the client
        id2request.remove(id)
        val client = clients.get(id)
        client ! AuthorizationDecision(result.result.decision)
        stats.tick
      } else {
        // the commit failed => restart the evaluation (add the necesary
        // attributes to the original again)
        log.warning(s"Conflicting evaluation found, restarting $id")
        val original = id2request(id)
        val updated = concurrencyController.restart(original)
        workQ.enqueue((updated, self))
      }

    /**
     *
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
}