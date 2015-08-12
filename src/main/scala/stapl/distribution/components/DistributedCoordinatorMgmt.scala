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

  /**
   * The list of all coordinators
   */ 
  private val coordinators = ListBuffer[(Int, ActorRef)]()
  
  /**
   * The list of active coordinators (which can differ from the list
   * of all coordinators after a DistributedCoordinatorConfigurationProtocol.SetNumberCoordinators
   * message.
   */
  private var activeCoordinators = List[(Int, ActorRef)]()

  /**
   * *********************************
   * ACTOR FUNCTIONALITY
   */
  def receive = {

    /**
     * From a new coordinator.
     *
     * Notice that there is a difference between $coordinator and $sender in case
     * this message was sent using ?.
     */
    case DistributedCoordinatorRegistrationProtocol.Register(coordinator) =>
      // add the coordinator to the administration
      coordinators += ((idCounter, coordinator))
      // NOTICE that this allows the list of coordinators to change after an
      // DistributedCoordinatorConfigurationProtocol.SetNumberCoordinators message
      // If this is not wanted, add messages to manage the state: fixed nb vs floating nb 
      // or something like that
      activeCoordinators = coordinators.toList
      // ack to the manager of the new coordinator (*including* the new coordinator itself)
      sender ! DistributedCoordinatorRegistrationProtocol.AckOfRegister(idCounter, coordinators.toList)
      // notify all but the new coordinator of the update 
      coordinators.init.foreach {
        case (id, coordinator) =>
          coordinator ! DistributedCoordinatorRegistrationProtocol.ListOfCoordinatorsWasUpdated(coordinators.toList)
      }
      // increment the id counter
      idCounter += 1

    /**
     * From a new client.
     */
    case ClientRegistrationProtocol.GetListOfCoordinators =>
      sender ! ClientRegistrationProtocol.ListOfCoordinators(activeCoordinators.toList)

    /**
     * Reconfiguration
     */
    case DistributedCoordinatorConfigurationProtocol.SetNumberCoordinators(nb) =>
      if (nb <= coordinators.size) {
        activeCoordinators = coordinators.toList.slice(0, nb)
        coordinators.foreach {
          case (id, coordinator) => coordinator ! DistributedCoordinatorRegistrationProtocol.ListOfCoordinatorsWasUpdated(activeCoordinators)
        }
        // FIXME race conditions here, we should probably wait for an ack by the coordinators?
        sender ! DistributedCoordinatorConfigurationProtocol.SetNumberCoordinatorsSuccess
      } else {
        sender ! DistributedCoordinatorConfigurationProtocol.SetNumberCoordinatorsFailed(
          s"You requested $nb coordinators, but I only have ${coordinators.size}")
      }

    /**
     *
     */
    case x => log.error(s"Unknown message received: $x")
  }
}

/**
 * A coordinator manager that waits for a number of coordinators to register
 * before giving the list of coordinators to clients.
 *
 * IMPORTANT NOTE: for now, the implementation is not thread-safe, does not take failures into account and
 * 		does not update the list of coordinators atomically.
 * 		Bottom line: only add coordinators before requests are sent, do not remove coordinators.
 */
class FixedNumberCoordinatorsDistributedCoordinatorManager(nbCoordinators: Int) extends Actor with ActorLogging {

  private var idCounter = 0

  private val coordinators = ListBuffer[(Int, ActorRef)]()

  private val waitingClients = ListBuffer[ActorRef]()

  /**
   * *********************************
   * ACTOR FUNCTIONALITY
   */
  def receive = {

    /**
     * From a new coordinator.
     *
     * Notice that there is a difference between $coordinator and $sender in case
     * this message was sent using ?.
     */
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
      // notify waiting clients if this was the last coordinator
      if (coordinators.size == nbCoordinators) {
        for (client <- waitingClients) {
          client ! ClientRegistrationProtocol.ListOfCoordinators(coordinators.toList)
        }
        waitingClients.clear
      }

    /**
     * From a new client.
     */
    case ClientRegistrationProtocol.GetListOfCoordinators =>
      if (coordinators.size == nbCoordinators) {
        sender ! ClientRegistrationProtocol.ListOfCoordinators(coordinators.toList)
      } else {
        waitingClients += sender
      }

    /**
     *
     */
    case x => log.error(s"Unknown message received: $x")
  }
}
