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

/**
 *
 */
abstract class DistributedCoordinatorManager(actorSystem: ActorSystem)
  extends CoordinatorLocater with CoordinatorGroup {

  override val coordinators = scala.collection.mutable.ListBuffer[ActorRef]()

  /**
   * Helper methods to update the local list to the values in Hazelcast
   */
  protected def addCoordinator(x: (String, Int)) = {
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
class HazelcastDistributedCoordinatorManager(hazelcast: HazelcastInstance, actorSystem: ActorSystem)
  extends DistributedCoordinatorManager(actorSystem) with ItemListener[(String, Int)] {

  import scala.collection.JavaConversions._
  import akka.serialization._

  /**
   * The Hazelcast list of IP addresses and ports on which Akka coordinators are listening.
   */
  private val backend = hazelcast.getList[(String, Int)]("stapl-coordinators")

  // set up the initial list of coordinators based on the list in Hazelcast
  backend foreach { addCoordinator(_) }

  // listen to updates to keep up-to-date
  backend.addItemListener(this, true)

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
   * Registers a DistributedCoordinator on the given IP address and port.
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
    Random.nextBoolean match {
      case true => getCoordinatorForSubject(request.subjectId)
      case false => getCoordinatorForResource(request.resourceId)
    }
  }
}

/**
 * A client for a distributed coordinator group on one or multiple other nodes. This client
 * fetches the concurrent coordinators on that node and sends authorization requests to the appropriate
 * coordinator on that node (this is the coordinator that manages the subject of the request).
 * 
 * FIXME there seems to be a race condition here: the order is not the same on every node every time...
 */
class RemoteDistributedCoordinatorGroup(hazelcast: HazelcastInstance, actorSystem: ActorSystem)
  extends CoordinatorGroup with CoordinatorLocater with Logging {

  import scala.collection.JavaConversions._
  import akka.serialization._

  /**
   * The Hazelcast list of IP addresses and ports on which Akka coordinators are listening.
   */
  private val backend = hazelcast.getList[(String, Int)]("stapl-coordinators")

  override val coordinators = scala.collection.mutable.ListBuffer[ActorRef]()

  // set up the initial list of coordinators based on the list in Hazelcast
  backend foreach { addCoordinator(_) }

  /**
   * Helper methods to update the local list to the values in Hazelcast
   */
  private def addCoordinator(x: (String, Int)) = {
    val (ip, port) = x
    // FIXME dit klopt niet meer
    val selection = actorSystem.actorSelection(s"akka.tcp://STAPL-coordinator@$ip:$port/user/coordinator")
    implicit val dispatcher = actorSystem.dispatcher
    val coordinator = Await.result(selection.resolveOne(3.seconds), 5.seconds)
    coordinators += coordinator
    info(s"Found and added coordinator at $x")
  }

  /**
   * Sends the given request to the coordinator responsible for managing
   * the subject of this request.
   */
  override def getCoordinatorFor(request: ClientCoordinatorProtocol.AuthorizationRequest): ActorRef = {
    getCoordinatorForSubject(request.subjectId)
  }
}