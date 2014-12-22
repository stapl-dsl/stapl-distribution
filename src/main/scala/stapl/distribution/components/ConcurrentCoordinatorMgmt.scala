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
import ConcurrentActorManagerProtocol.Coordinators
import ConcurrentActorManagerProtocol.GetCoordinators
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.pattern.ask
import akka.util.Timeout
import stapl.core.RESOURCE
import stapl.core.SUBJECT
import stapl.distribution.db.AttributeDatabaseConnectionPool
import scala.util.Failure
import scala.util.Success
import grizzled.slf4j.Logging

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
trait CoordinatorLocater {

  /**
   * The list of coordinators
   */
  def coordinators: Seq[ActorRef]

  /**
   * Returns the Coordinator that manages the evaluations for the
   * given subject.
   */
  def getCoordinatorForSubject(entityId: String) = {
    val key = SUBJECT + ":" + entityId
    val hash = Math.abs(key.hashCode())
    coordinators(hash % coordinators.size)
  }

  /**
   * Returns the Coordinator that manages the evaluations for the
   * given subject.
   */
  def getCoordinatorForResource(entityId: String) = {
    val key = RESOURCE + ":" + entityId
    val hash = Math.abs(key.hashCode())
    coordinators(hash % coordinators.size)
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
      SUBJECT
    } else if (coordinatorForResource == coordinator) {
      RESOURCE
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
 *
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

  import ConcurrentActorManagerProtocol._
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

  import ConcurrentActorManagerProtocol._
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

  /**
   * Sends the given request to the coordinator responsible for managing
   * the subject of this request.
   */
  override def getCoordinatorFor(request: ClientCoordinatorProtocol.AuthorizationRequest): ActorRef = {
    getCoordinatorForSubject(request.subjectId)
  }
}

/**
 * Class used for managing the different coordinators on the different nodes of the whole system.
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
class DistributedCoordinatorManager(hazelcast: HazelcastInstance, actorSystem: ActorSystem)
  extends CoordinatorLocater with CoordinatorGroup with ItemListener[(String, Int)] {

  import scala.collection.JavaConversions._
  import akka.serialization._

  /**
   * The Hazelcast list of IP addresses and ports on which Akka coordinators are listening.
   */
  private val backend = hazelcast.getList[(String, Int)]("stapl-coordinators")

  override val coordinators = scala.collection.mutable.ListBuffer[ActorRef]()

  // set up the initial list of coordinators based on the list in Hazelcast
  backend foreach { addCoordinator(_) }

  // listen to updates to keep up-to-date
  backend.addItemListener(this, true)

  /**
   * Helper methods to update the local list to the values in Hazelcast
   */
  private def addCoordinator(x: (String, Int)) = {
    val (ip, port) = x
    // FIXME dit klopt niet meer
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
    backend.add((ip, port))
    // the registered actor will be added to $coordinators by itemAdded()
  }

  /**
   * Sends the given request to the coordinator responsible for managing
   * either the subject or resource of this request. The final coordinator
   * is chosen at random in order to divide the requests approximately evenly.
   */
  override def getCoordinatorFor(request: ClientCoordinatorProtocol.AuthorizationRequest): ActorRef = {
    import ClientCoordinatorProtocol.AuthorizationRequest
    Random.nextBoolean match {
      case true => getCoordinatorForSubject(request.subjectId)
      case false => getCoordinatorForResource(request.resourceId)
    }
  }
}