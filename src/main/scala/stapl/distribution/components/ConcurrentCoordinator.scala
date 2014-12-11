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
import stapl.core.SUBJECT
import stapl.core.RESOURCE
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
import scala.util.{ Success, Failure }

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
class ConcurrentConcurrencyController(coordinator: ActorRef, updateWorkers: List[ActorRef], log: LoggingAdapter) {

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
   * A multimap that stores what we manage for a certain request: the subject,
   * the resource or both.
   *
   * This is only needed for restarting the request.
   */
  val weManage = new HashMap[String, Set[AttributeContainerType]] with MultiMap[String, AttributeContainerType]

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
   * request is going to start and that this controller should do
   * the administration for the subject of the request.
   *
   * This method adds the original request with the appropriate
   * attributes added. These attributes are attributes that are
   * currently being updated as the result of previous policy evaluations
   * but are not sure to bhe committed in the database yet.
   */
  def startForSubject(request: PolicyEvaluationRequest): PolicyEvaluationRequest = {
    // set up the administration
    ongoingEvaluations(request.id) = request
    subjectId2Evaluation.addBinding(request.subjectId, request)
    weManage.addBinding(request.id, SUBJECT)
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
    ongoingEvaluations(request.id) = request
    resourceId2Evaluation.addBinding(request.resourceId, request)
    weManage.addBinding(request.id, RESOURCE)
    updatesWhileEvaluating(request.id) = ListBuffer()

    // add suitable attributes of ongoing attribute updates
    addSuitableAttributes(request)
  }

  /**
   * Indicates to the controller that the evaluation of the given request
   * is going to restart. This resets the list of updates while evaluating
   * the request and returns the given request with the appropriate
   * attributes added (see start()).
   *
   * Note: if we manage both the subject and the resource of this request,
   * this method will have added the necessary attributes for both
   */
  def restart(request: PolicyEvaluationRequest): PolicyEvaluationRequest = {
    // reset the administration
    updatesWhileEvaluating(request.id) = ListBuffer()
    // add suitable attributes of ongoing attribute updates
    // Note: if we manage both the subject and the resource of this request,
    // 		 this method will have added the necessary attributes for both
    addSuitableAttributes(request)
  }

  private def addSuitableAttributes(request: PolicyEvaluationRequest): PolicyEvaluationRequest = {
    var attributes = request.extraAttributes
    // Note: subjectId2OngoingUpdates will only contain this request if
    // we should also manage this request => this method can be used from
    // both the subject-specific and resource-specific methods.
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

    // TODO HIER MOET NOG VEEL AAN VERANDEREN
    // - commit enkel de updates van de entiteit die wij wel degelijk managen!
    // - check of we enkel het subject of de resource van het request managen
    //		of beide. If beide: doe de hele commit nu. If slechts eens: overleg
    // 		met de andere coordinator

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
          subjectId2Evaluation(request.subjectId) foreach { x =>
            updatesWhileEvaluating(x.id) += change.attribute
            log.debug(s"Stored the possibly conflicting attribute update for the SUJBECT with evaluation ${x.id}")
          }
          resourceId2Evaluation(request.resourceId) foreach { x =>
            updatesWhileEvaluating(x.id) += change.attribute
            log.debug(s"Stored the possibly conflicting attribute update for the RESOURCE with evaluation ${x.id}")
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
      case SUBJECT => subjectId2OngoingUpdates
      case RESOURCE => resourceId2OngoingUpdates
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
      case SUBJECT => subjectId2OngoingUpdates
      case RESOURCE => resourceId2OngoingUpdates
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
 * Trait used for representing a group of coordinators.
 */
trait CoordinatorGroup {

  /**
   * Sends the request to this group of coordinators. Behind the scenes,
   * this may mean: send it to a random coordinator, to the approrpriate
   * coordinator, to all of them,...
   */
  def !(request: ClientCoordinatorProtocol.AuthorizationRequest)
}

/**
 * Class used for managing the different coordinators in the whole system.
 *
 * We store this list locally in order to speed up finding the correct coordinator
 * for a certain request (although I am not sure whether Hazelcast requires
 * network communication to read the list). In order to stay up-to-date with
 * new members, we also listen to events on the list.
 *
 * Note TODO: we currently do not take into account coordinator failure
 *
 * Note: we currently do not support adding a new coordinator when the system is
 * in operation, i.e., the system will probably work correctly, but correct concurrency
 * control is not guaranteed for the evaluation that are ongoing when a coordinator
 * joins.
 */
class ConcurrentCoordinatorManager(hazelcast: HazelcastInstance, actorSystem: ActorSystem) extends CoordinatorGroup with ItemListener[(String, Int)] {

  import scala.collection.JavaConversions._
  import akka.serialization._

  /**
   * The Hazelcast list of IP addresses and ports on which Akka coordinators are listening.
   */
  private val backend = hazelcast.getList[(String, Int)]("stapl-coordinators")

  private var coordinators = scala.collection.mutable.ListBuffer[ActorRef]()

  // set up the initial list of coordinators based on the list in Hazelcast
  backend foreach { addCoordinator(_) }

  // listen to updates to keep up-to-date
  backend.addItemListener(this, true)

  /**
   * Helper methods to update the local list to the values in Hazelcast
   */
  private def addCoordinator(x: (String, Int)) = {
    val (ip, port) = x
    val selection = actorSystem.actorSelection(s"akka.tcp://STAPL-coordinator@$ip:$port/user/coordinator")
    implicit val dispatcher = actorSystem.dispatcher
    selection.resolveOne(3.seconds).onComplete {
      case Success(coordinator) =>
        coordinators += coordinator
        println(s"Found and added coordinator at $x")
      case Failure(t) =>
        println(s"Did not find coordinator at $x: ")
        t.printStackTrace()
    }
  }

  /**
   * When an item is added, just reset the list
   */
  def itemAdded(e: ItemEvent[(String, Int)]): Unit = {
    addCoordinator(e.getItem())
  }

  /**
   *
   */
  def itemRemoved(e: ItemEvent[(String, Int)]): Unit = {
    // TODO implement
  }

  /**
   * Registers the given Actor as ConcurrentCoordinator.
   */
  def register(ip: String, port: Int): Unit = {
    backend.add((ip,port))
    // the registered actor will be added to $coordinators by itemAdded()
  }

  /**
   * Returns the Coordinator that manages the evaluations for the
   * given subject.
   */
  def getCoordinatorForSubject(entityId: String) = {
    val key = SUBJECT + ":" + entityId
    val hash = key.hashCode()
    coordinators(hash % coordinators.size)
  }

  /**
   * Returns the Coordinator that manages the evaluations for the
   * given subject.
   */
  def getCoordinatorForResource(entityId: String) = {
    val key = RESOURCE + ":" + entityId
    val hash = key.hashCode()
    coordinators(hash % coordinators.size)
  }

  /**
   * Sends the given request to the coordinator responsible for managing
   * either the subject or resource of this request. The final coordinator
   * is chosen at random in order to divide the requests approximately evenly.
   */
  override def !(request: ClientCoordinatorProtocol.AuthorizationRequest) {
    import ClientCoordinatorProtocol.AuthorizationRequest
    import ClientConcurrentCoordinatorProtocol._
    val ClientCoordinatorProtocol.AuthorizationRequest(subjectId, actionId, resourceId, extraAttributes) = request
    Random.nextBoolean match {
      case true => getCoordinatorForSubject(subjectId) ! AuthorizationRequestAndManageSubject(subjectId, actionId, resourceId, extraAttributes)
      case false => getCoordinatorForResource(resourceId) ! AuthorizationRequestAndManageResource(subjectId, actionId, resourceId, extraAttributes)
    }
  }
}

/**
 * Class used for representing the Coordinator that manages all foremen
 * and ensures correct concurrency.
 *
 * TODO: the work mgmt is not correct yet: the list of requests assigned
 * to a foremen is not extended with newly assigned work and is also not cleared
 * if the foreman has finished certain jobs except if he sends a "Finished" message
 * (so not in the "give me more" message)
 */
class ConcurrentCoordinator(coordinatorId: Long, pool: AttributeDatabaseConnectionPool, nbUpdateWorkers: Int,
  coordinatorManager: ConcurrentCoordinatorManager) extends Actor with ActorLogging {

  import ClientConcurrentCoordinatorProtocol._
  import CoordinatorForemanProtocol._
  import ConcurrentCoordinatorProtocol._

  /**
   *  Holds known workers and what they may be working on
   */
  private val foremen = new ForemanManager

  /**
   * Holds the incoming list of work to be done as well
   * as the memory of who asked for it
   */
  private val workQ = Queue.empty[PolicyEvaluationRequest]

  /**
   * Holds the mapping between the clients and the authorization requests
   * they sent.
   */
  private val clients: Map[String, ActorRef] = Map()

  /**
   * Some statistics of the throughput
   */
  private val stats = new ThroughputStatistics

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
    updateWorkers += context.actorOf(Props(classOf[UpdateWorker], self, pool.getConnection))
  }
  private val concurrencyController = new ConcurrentConcurrencyController(self, updateWorkers.toList, log)

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
        val workBuffer = ListBuffer[PolicyEvaluationRequest]()
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
     *  A worker died.  If he was doing anything then we need
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
     * A clients sends an authorization request and indicates that this coordinator
     * should manage either the subject.
     */
    case AuthorizationRequestAndManageSubject(subjectId, actionId, resourceId, extraAttributes) =>
      val client = sender
      // log
      log.debug(s"Queueing ($subjectId, $actionId, $resourceId) from $client (I should manage the subject)")
      // this is a request from a client => construct an id
      val id = constructNextId()
      // add the attributes according to our own administration
      val original = new PolicyEvaluationRequest(id, Top, subjectId, actionId, resourceId, extraAttributes)
      val updated = concurrencyController.startForSubject(original)
      // forward the request to the coordinator managing the rest of the administration
      coordinatorManager.getCoordinatorForSubject(subjectId) ! StartRequestAndManageResource(self, client, original, updated)

    /**
     * A clients sends an authorization request and indicates that this coordinator
     * should manage either the resource.
     */
    case AuthorizationRequestAndManageResource(subjectId, actionId, resourceId, extraAttributes) =>
      val client = sender
      // log
      log.debug(s"Queueing ($subjectId, $actionId, $resourceId, $extraAttributes) from $client (I should manage the resource)")
      // this is a request from a client => construct an id
      val id = constructNextId()
      // add the attributes according to our own administration
      val original = new PolicyEvaluationRequest(id, Top, subjectId, actionId, resourceId, extraAttributes)
      val updated = concurrencyController.startForResource(original)
      // forward the request to the coordinator managing the rest of the administration
      coordinatorManager.getCoordinatorForResource(resourceId) ! StartRequestAndManageSubject(self, client, original, updated)

    /**
     * Another coordinator sends a request for us to manage the subject and evaluate
     * the given request.
     */
    case StartRequestAndManageSubject(sendingCoordinator, client, original, updated) =>
      // log
      log.debug(s"Queueing $updated from $client via $sendingCoordinator (I should manage the subject)")
      // store the client to forward the result later on
      clients(original.id) = client
      // add the attributes according to our own administration
      val updatedAgain = concurrencyController.startForSubject(updated)
      // evaluate the request
      id2request(original.id) = original
      workQ.enqueue(updatedAgain)
      notifyForemen

    /**
     * Another coordinator sends a request for us to manage the resource and evaluate
     * the given request.
     */
    case StartRequestAndManageResource(sendingCoordinator, client, original, updated) =>
      // log
      log.debug(s"Queueing $updated from $client via $sendingCoordinator (I should manage the resource)")
      // store the client to forward the result later on
      clients(original.id) = client
      // add the attributes according to our own administration
      val updatedAgain = concurrencyController.startForResource(updated)
      // evaluate the request
      id2request(original.id) = original
      workQ.enqueue(updatedAgain)
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
        val client = clients(id)
        client ! ClientCoordinatorProtocol.AuthorizationDecision(result.result.decision)
        stats.tick
      } else {
        log.warning(s"Conflicting evaluation found, restarting $id")
        // the commit failed => restart the evaluation by resetting our administration,
        // adding the appropriate attributes to the original and sending this to the 
        // appropriate coordinator
        val original = id2request(id)
        val updated = concurrencyController.restart(original)
        val weManage = concurrencyController.weManage(id)
        if (weManage.size == 2) {
          // We can start this evaluation right now! 
          // => do not clean up any administration          
          workQ.enqueue(updated)
          notifyForemen
        } else if (weManage.contains(SUBJECT)) {
          val client = clients(original.id)
          // clean up the administration
          clients.remove(original.id)
          id2request.remove(original.id)
          // send the request to the other coordinator
          coordinatorManager.getCoordinatorForResource(original.resourceId) ! RestartRequestAndManageResource(self, client, original, updated)
        } else {
          // weManage.contains(RESOURCE)
          val client = clients(original.id)
          // clean up the administration
          clients.remove(original.id)
          id2request.remove(original.id)
          // send the request to the other coordinator
          coordinatorManager.getCoordinatorForSubject(original.subjectId) ! RestartRequestAndManageSubject(self, client, original, updated)
        }
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