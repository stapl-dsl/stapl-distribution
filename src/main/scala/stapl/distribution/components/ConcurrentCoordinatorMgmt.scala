package stapl.distribution.components

import scala.collection.JavaConversions.asScalaBuffer
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Random
import scala.util.Success
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.ItemEvent
import com.hazelcast.core.ItemListener
import ConcurrentCoordinatorManagerProtocol.Coordinators
import ConcurrentCoordinatorManagerProtocol.GetCoordinators
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.pattern.ask
import akka.util.Timeout
import stapl.distribution.db.AttributeDatabaseConnectionPool
import scala.util.Failure
import scala.util.Success
import grizzled.slf4j.Logging
import stapl.distribution.util.Counter

/**
 * Trait used for representing a group of coordinators.
 */
trait CoordinatorGroup {

  /**
   * Returns the coordinator to send the given request to. This can be
   * a random coordinator, a single appropriate coordinator, ...
   */
  def getCoordinatorFor(request: ClientCoordinatorProtocol.AuthorizationRequest): ActorRef
}

/**
 * Trait used for locating coordinators.
 */
trait CoordinatorLocater extends CoordinatorGroup with Logging {

  /**
   * The list of coordinators
   */
  def coordinators: Seq[ActorRef]

  /**
   * Returns the Coordinator that manages the evaluations for the
   * given subject.
   */
  def getCoordinatorForSubject(entityId: String) = {
    val key = "SUBJECT:" + entityId
    val hash = Math.abs(key.hashCode())
    val i = hash % coordinators.size
    val coordinator = coordinators(i) 
    debug(s"Chose to send request to coordinator $i ($coordinator)")
    coordinator
  }

  /**
   * Returns the Coordinator that manages the evaluations for the
   * given subject.
   */
  def getCoordinatorForResource(entityId: String) = {
    val key = "RESOURCE:" + entityId
    val hash = Math.abs(key.hashCode())
    coordinators(hash % coordinators.size)
  }

  /**
   * Sends the given request to the coordinator responsible for managing
   * the subject of this request.
   */
  override def getCoordinatorFor(request: ClientCoordinatorProtocol.AuthorizationRequest): ActorRef = {
    getCoordinatorForSubject(request.subjectId)
  }

  /**
   * Returns what the given coordinator should manage for the given request.
   */
  def whatShouldIManage(coordinator: ActorRef, request: PolicyEvaluationRequest): Managed = {
    val coordinatorForSubject = getCoordinatorForSubject(request.subjectId)
    val coordinatorForResource = getCoordinatorForResource(request.resourceId)
    if (coordinatorForSubject == coordinator && coordinatorForResource == coordinator) {
      BOTH
    } else if (coordinatorForSubject == coordinator) {
      ONLY_SUBJECT
    } else if (coordinatorForResource == coordinator) {
      ONLY_RESOURCE
    } else {
      NOTHING
    }
  }
}

/**
 * Class used for managing the different concurrent coordinators on a single node.
 *
 * Note: this class is not thread-safe, so do not add new coordinators while it is
 * actively in use.
 */
class ConcurrentCoordinatorLocator extends CoordinatorLocater {

  override val coordinators = scala.collection.mutable.ListBuffer[ActorRef]()

  /**
   * Registers the given Actor as local ConcurrentCoordinator.
   */
  def register(cs: ActorRef*): Unit = {
    coordinators ++= cs
  }
}

/**
 *  This is the actor that sets up and manages multiple concurrent coordinators on a single node.
 */
class ConcurrentCoordinatorManager(nbCoordinators: Int, pool: AttributeDatabaseConnectionPool, nbUpdateWorkers: Int)
  extends Actor with ActorLogging {

  // set up our foreman manager
  val foremanManager = context.actorOf(Props(classOf[ForemanManager]), "foreman-manager")

  // set up our coordinators
  private val coordinatorLocator = new ConcurrentCoordinatorLocator
  for (i <- 1 to nbCoordinators) {
    val coordinator = context.actorOf(Props(classOf[ConcurrentCoordinator], i.toLong, pool, nbUpdateWorkers, coordinatorLocator, foremanManager))
    coordinatorLocator.register(coordinator)
  }

  import ConcurrentCoordinatorManagerProtocol._
  def receive = {

    /**
     * A client requests the coordinators that we manage so that it can directly send to
     * the appropriate coordinator.
     */
    case GetCoordinators =>
      sender ! Coordinators(coordinatorLocator.coordinators)

    /**
     * Just to be sure
     */
    case x =>
      log.warning(s"Unknown message received: $x")
  }
}

/**
 * A client for a concurrent coordinator group on another node that fetches the concurrent
 * coordinators on that node and sends authorization requests to the appropriate
 * coordinator on that node (this is the coordinator that manages the subject of the request).
 */
class RemoteConcurrentCoordinatorGroup(actorSystem: ActorSystem, ip: String, port: Int)
  extends CoordinatorGroup with CoordinatorLocater with Logging {

  override val coordinators = scala.collection.mutable.ListBuffer[ActorRef]()

  import ConcurrentCoordinatorManagerProtocol._
  implicit val timeout = Timeout(3.second)
  implicit val dispatcher = actorSystem.dispatcher

  // contact the remote ConcurrentCoordinatorManager to fill up our list of coordinators
  val selection = actorSystem.actorSelection(s"akka.tcp://STAPL-coordinator@$ip:$port/user/coordinator-manager")
  val coordinatorManager = Await.result(selection.resolveOne(1.second), 3.seconds) // not sure what the difference between these durations is
  Await.result(coordinatorManager ? GetCoordinators, 3.seconds) match {
    case Coordinators(cs) =>
      coordinators ++= cs
      debug(s"Found coordinator manager at $ip:$port with ${cs.size} coordinators")
    case x =>
      error(s"Unknown message received: $x")
  }
}