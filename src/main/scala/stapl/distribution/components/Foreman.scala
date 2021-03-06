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

import akka.actor.ActorRef
import stapl.core.Permit
import stapl.core.Result
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import scala.concurrent.duration._
import scala.util.{ Success, Failure }
import akka.actor.Props
import CoordinatorForemanProtocol.PolicyEvaluationResult
import akka.actor.actorRef2Scala
import scala.math.BigInt.int2bigInt
import akka.actor.ActorLogging
import akka.actor.Actor
import scala.collection.mutable.Queue
import scala.collection.mutable.Set
import stapl.core.AbstractPolicy
import stapl.distribution.db.AttributeDatabaseConnectionPool
import stapl.distribution.db.AttributeDatabaseConnection
import stapl.distribution.db.AttributeDatabaseConnectionPool
import stapl.distribution.util.ThroughputStatistics
import stapl.distribution.util.ThroughputAndLatencyStatistics

/**
 * Class used for managing workers.
 */
class WorkerManager {

  val workers = Set.empty[ActorRef]
  val busyWorkers = Set.empty[ActorRef]
  val idleWorkers = Set.empty[ActorRef]

  /**
   * The total number of workers
   */
  def size = workers.size

  /**
   * Add a new worker (as idle).
   */
  def +=(worker: ActorRef) = {
    workers += worker
    idleWorkers += worker
  }

  /**
   * Remove a worker.
   */
  def -=(worker: ActorRef) = {
    workers -= worker
    idleWorkers -= worker
    busyWorkers -= worker
  }

  /**
   * Return all idle workers.
   */
  def idle = idleWorkers

  /**
   * Set the given worker as busy.
   */
  def setBusy(worker: ActorRef) {
    idleWorkers -= worker
    busyWorkers += worker
  }

  /**
   * Set the given worker as idle.
   */
  def setIdle(worker: ActorRef) {
    idleWorkers += worker
    busyWorkers -= worker
  }
}

/**
 * Class used for representing the actor on a machine that communicates with
 * the coordinator and distributes the work received from that coordinator
 * amongst multiple policy evaluator actors on its machine.
 *
 * @param	bufferFactor
 * 			The buffer factor determines how many messages we want in our buffer per worker.
 * 			For example, a buffer factor of 2 for a foreman with 4 workers, will lead to
 * 			requesting 2*4=8 requests from the coordinator every time, and will lead to a
 * 			preventive refill of again 8 requests when the internal buffer contains less than
 * 			8 requests yet. => a higher buffer factor leads to more efficient communication,
 *    		but more latency in case the system is not 100% under load since the excess
 *      	request could have been	handled by another foreman in parallel.
 */
class Foreman(coordinator: ActorRef, nbWorkers: Int, policy: AbstractPolicy, pool: AttributeDatabaseConnectionPool,
  bufferFactor: Int = 2, mockEvaluation: Boolean = false, mockEvaluationDuration: Int = 0) extends Actor with ActorLogging {

  //  /**
  //   * The database connections for the workers
  //   */
  //  val pool = new AttributeDatabaseConnectionPool("localhost", 3306, "stapl-attributes", "root", "root", true /* readonly */)

  /**
   * The queues of work for our Workers: one queue of work received by the
   * foreman, one queue of work received by the workers (intermediate requests).
   */
  val externalWorkQ = Queue.empty[(PolicyEvaluationRequest, ActorRef)]
  val internalWorkQ = Queue.empty[(PolicyEvaluationRequest, ActorRef)]

  /**
   * The management of our workers.
   */
  val workers = new WorkerManager

  /**
   * Some statistics of the throughput
   */
  private val stats = new ThroughputAndLatencyStatistics("foreman stats", 5000)

  /**
   * Returns whether we should preventively request more work because of
   * the current contents of the queues and the current number of workers.
   */
  def shouldPreventivelyRefill =
    // our strategy: count the number of requests from the coordinator and the
    // number of requests of internal workers and compare that to the number of
    // workers at hand so that there is always new work for the workers to work on
    // when they finish. However, requests from the coordinator are likely to 
    // take longer than intermediate policy evaluation requests of the workers,
    // so weigh those less.
    (externalWorkQ.size + math.floor(internalWorkQ.size / 2.0)) <= workers.size * bufferFactor

  /**
   * Notifies workers that there's work available, provided they're
   * not already working on something
   */
  def notifyWorkers(): Unit = {
    if (!(externalWorkQ.isEmpty && internalWorkQ.isEmpty)) {
      // we have some work, be it received from a worker or from a foreman
      workers.idle foreach { _ ! ForemanWorkerProtocol.WorkIsReady }
    }
  }

  /**
   *  Notify the coordinator that we're alive
   */
  override def preStart() = {
    // create our workers
    1 to nbWorkers foreach { _ =>
      workers += context.actorOf(Props(classOf[Worker], self, policy, null, pool.getConnection, /* enableStatsDb */ false,
        mockEvaluation, mockEvaluationDuration))
    }
    // notify the coordinator
    coordinator ! CoordinatorForemanProtocol.ForemanCreated(self)
  }

  /**
   *  This is the state we're in when we're working on something.
   *  In this state we can deal with messages in a much more
   *  reasonable manner.
   */
  def receive = {
    /**
     * From the Coordinator.
     *
     * Fetch work if we have spare workers
     */
    case CoordinatorForemanProtocol.WorkIsReady =>
      log.debug(s"The coordinator said that work is ready")
      // TODO now we request work for the idle workers, but probably
      // we should request some more so that workers that finish can
      // immediately start working on something
      coordinator ! CoordinatorForemanProtocol.ForemanRequestsWork(self, workers.idle.size)

    /**
     * From the Coordinator.
     *
     * We will only receive this when we asked for it => add the work to the queue
     */
    case CoordinatorForemanProtocol.WorkToBeDone(requests: List[(PolicyEvaluationRequest, coordinator)]) =>
      log.debug(s"The coordinator sent work: $requests")
      for ((request, client) <- requests) {
        log.debug(s"[Evaluation ${request.id}] Received request at foreman")
      }
      externalWorkQ ++= requests
      notifyWorkers

    /**
     * From a Worker.
     *
     * Give him more work if we have some.
     */
    case ForemanWorkerProtocol.WorkerIsDoneAndRequestsWork(worker) =>
      log.debug(s"A worker finished his work: $worker")
      stats.stop(worker)
      workers.setIdle(worker)
      self ! ForemanWorkerProtocol.WorkerRequestsWork(worker)
      // keep the coordinator up-to-date and ask for more work if needed
      if (externalWorkQ.isEmpty && internalWorkQ.isEmpty) {
        log.debug("We're out of work, notify the coordinator of this")
        coordinator ! CoordinatorForemanProtocol.ForemanIsDoneAndRequestsWork(self, workers.size * bufferFactor)
      } else if (shouldPreventivelyRefill) {
        log.debug(s"We're not ouf of work yet, but ask for more anyway")
        coordinator ! CoordinatorForemanProtocol.ForemanRequestsWork(self, workers.size * bufferFactor)
      }

    /**
     * From a Worker.
     *
     * Give him work if we have some.
     */
    case ForemanWorkerProtocol.WorkerRequestsWork(worker) =>
      if (workers.idleWorkers.contains(worker)) {
        log.debug(s"An idle worker requests work: $worker => sending him some work")
        // our prioritization between internal requests and external requests:
        // give internal requests priority in order to keep the total latency
        // of policy evaluations minimal
        if (!internalWorkQ.isEmpty) {
          val (request, coordinator) = internalWorkQ.dequeue
          worker ! ForemanWorkerProtocol.WorkToBeDone(request, coordinator)
          workers.setBusy(worker)
          stats.start(worker)
          log.debug(s"[Evaluation ${request.id}] Sent request to worker $worker for evaluation")
        } else if (!externalWorkQ.isEmpty) {
          val (request, coordinator) = externalWorkQ.dequeue
          worker ! ForemanWorkerProtocol.WorkToBeDone(request, coordinator)
          workers.setBusy(worker)
          stats.start(worker)
          log.debug(s"[Evaluation ${request.id}] Sent request to worker $worker for evaluation")
        }
      } else {
        log.debug(s"A busy worker requests work: $worker => not sending him work")
      }

    /**
     * To be sure
     */
    case x => log.error(s"Unknown message received: $x")
  }
}