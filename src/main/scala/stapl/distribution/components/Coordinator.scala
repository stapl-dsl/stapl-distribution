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

/**
 * Class used for temporarily storing the clients that sent an
 * authorization request so that we can pass the decision back
 * to the client later on.
 */
class ClientAuthorizationRequestManager {

  import scala.collection.mutable.Map

  private var counter = 0

  private val clients: Map[Int, ActorRef] = Map()

  /**
   * Stores the actor in the cache and returns the generated
   * id of its authorization request.
   */
  def store(actor: ActorRef) = {
    val id = counter
    clients(id) = actor
    counter += 1
    id
  }

  /**
   * Returns the actor that sent the authorization request with the given id
   * and removes this actor from the map.
   */
  def get(id: Int) = {
    val result = clients(id)
    clients.remove(id)
    result
  }
}

/**
 * Class used for managing foremen.
 */
class ForemanManager {

  val foremen = Map.empty[ActorRef, Option[List[PolicyEvaluationRequest]]]

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
  def foremanStartedWorkingOn(foreman: ActorRef, work: List[PolicyEvaluationRequest]) =
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
 * Class used for representing the Coordinator that manages all foremen
 * and ensures correct concurrency.
 */
class Coordinator extends Actor with ActorLogging {
  import ClientCoordinatorProtocol._
  import CoordinatorForemanProtocol._

  /**
   *  Holds known workers and what they may be working on
   */
  val foremen = new ForemanManager

  /**
   * Holds the incoming list of work to be done as well
   * as the memory of who asked for it
   */
  val workQ = Queue.empty[PolicyEvaluationRequest]

  /**
   * Holds the mapping between the clients and the authorization requests
   * they sent.
   */
  val clients = new ClientAuthorizationRequestManager

  /**
   * Some statistics of the throughput
   */
  val stats = new ThroughputStatistics

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
     *
     */
    case AuthorizationRequest(subjectId, actionId, resourceId) =>
      log.debug(s"Queueing ($subjectId, $actionId, $resourceId) from $sender")
      val id = clients.store(sender)
      workQ.enqueue(new PolicyEvaluationRequest(id, Top, subjectId, actionId, resourceId))
      notifyForemen

    /**
     *
     */
    case PolicyEvaluationResult(id, result) =>
      log.debug(s"Received authorization decision: ($id, $result)")
      val client = clients.get(id)
      // TODO: fulfill obligations here
      client ! AuthorizationDecision(result.decision)
      stats.tick

    /**
     * Unknown messages
     */
    case x => log.error(s"Unknown message received: $x")

  }
}