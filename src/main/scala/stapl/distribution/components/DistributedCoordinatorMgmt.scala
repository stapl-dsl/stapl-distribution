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
import scala.collection.mutable.ListBuffer

/**
 *
 */
class SimpleDistributedCoordinatorLocater extends CoordinatorLocater with CoordinatorGroup {

  override val coordinators = scala.collection.mutable.ListBuffer[ActorRef]()

  /**
   * Helper method to update the whole list of coordinators at once.
   *
   * This method is NOT thread-safe if the list is being read at the same time
   * as well.
   */
  def setCoordinators(cs: List[ActorRef]) {
    coordinators.clear
    coordinators ++= cs
  }
}

/**
 *
 */
class AbstractDistributedCoordinatorLocater(actorSystem: ActorSystem)
  extends CoordinatorLocater with CoordinatorGroup {

  override val coordinators = scala.collection.mutable.ListBuffer[ActorRef]()

  /**
   * Helper methods to add a coordinator to the list of coordinators.
   */
  protected def addCoordinator(x: (String, Int)) = {
    val (ip, port) = x
    // FIXME dit klopt niet meer
    val selection = actorSystem.actorSelection(s"akka.tcp://STAPL-coordinator@$ip:$port/user/coordinator")
    implicit val dispatcher = actorSystem.dispatcher
    val coordinator = Await.result(selection.resolveOne(3.seconds), 5.seconds)
    coordinators += coordinator
    println(s"Found and added coordinator at $x")
    //        println(s"Did not find coordinator at $x: ")
    //        t.printStackTrace()
  }

  /**
   * Helper method to update the whole list of coordinators at once.
   *
   * This method is NOT thread-safe if the list is being read at the same time
   * as well.
   */
  def setCoordinators(cs: List[ActorRef]) {
    coordinators.clear
    coordinators ++= cs
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
 * Note: TODO: we currently do not take into account coordinator failure
 *
 * Note: TODO: we currently do not support adding a new coordinator when the system is
 * in operation, i.e., the system will probably work correctly, but correct concurrency
 * control is not guaranteed for the evaluation that are ongoing when a coordinator
 * joins.
 *
 * FIXME there seems to be a race condition here: the order is not the same on every node every time...
 */
class HazelcastDistributedCoordinatorLocater(hazelcast: HazelcastInstance, actorSystem: ActorSystem)
  extends AbstractDistributedCoordinatorLocater(actorSystem) with ItemListener[(String, Int)] {

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
}

/**
 * A distributed coordinator manager that does not update its values at run-time, but
 * is just given a list of coordinator locations at initialization.
 */
class HardcodedDistributedCoordinatorLocater(actorSystem: ActorSystem, coordinators: (String, Int)*)
  extends AbstractDistributedCoordinatorLocater(actorSystem) {

  /**
   * Initialize the ActorRefs (only now will the IP and ports be mapped to ActorRefs,
   * do this after the remote Actors are up and running).
   */
  def initialize() {
    coordinators.foreach(location =>
      addCoordinator(location))
  }
}

/**
 * An actor that manages a cluster of distributed coordinators.
 *
 * @param 	locator
 * 			The DistributedCoordinatorLocator in which this DistributedCoordinatorManager
 *    		will maintain the list of coordinators. This Locator should be shared with the
 *      	coordinator behind the given ActorRef.
 * @param	coordinator
 * 			The Coordinator for which this DistributedCoordinatorManager manages the locater.
 *
 * IMPORTANT NOTE: for now, the implementation is not thread-safe, does not take failures into account and
 * 		does not update the list of coordinators atomically.
 * 		Bottom line: only add coordinators before requests are sent, do not remove coordinators.
 */
class DistributedCoordinatorManager extends Actor with ActorLogging {

  private var idCounter = 0

  private val coordinators = ListBuffer[(Int, ActorRef)]()

  /**
   * *********************************
   * ACTOR FUNCTIONALITY
   */
  def receive = {

    case DistributedCoordinatorRegistrationProtocol.Register(coordinator) =>
      // add the coordinator to the administration
      coordinators += ((idCounter, coordinator))
      // ack to the manager of the new coordinator (*including* the new coordinator itself)
      sender ! DistributedCoordinatorRegistrationProtocol.AckOfRegister(idCounter, coordinators.toList)
      // notify all but the new coordinator of the update 
      coordinators.init.foreach {
        case (id, coordinator) =>
          coordinator ! DistributedCoordinatorRegistrationProtocol.ListOfCoordinatorsWasUpdated(coordinators.toList)
      }
      // increment the id counter
      idCounter += 1
  }
}
