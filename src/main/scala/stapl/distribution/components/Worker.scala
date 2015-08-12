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

import akka.actor.ActorSystem
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Future
import org.joda.time.LocalDateTime
import stapl.core.AbstractPolicy
import stapl.distribution.concurrency.ConcurrentAttributeCache
import stapl.distribution.db.AttributeDatabaseConnection
import stapl.core.pdp.AttributeFinder
import stapl.distribution.db.DatabaseAttributeFinderModule
import stapl.core.pdp.PDP
import stapl.core.Result
import stapl.core.Permit
import stapl.distribution.db.HardcodedEnvironmentAttributeFinderModule
import stapl.distribution.db.LegacyAttributeDatabaseConnection
import stapl.distribution.db.AttributeDatabaseConnection
import stapl.core.Result
import stapl.core.Deny
import scala.concurrent.blocking
import stapl.distribution.util.Tracer

/**
 * The Scala actor that wraps a PDP and is able to evaluate policies on request of a Foreman.
 * 
 * TODO: remove the cache
 */
class Worker(foreman: ActorRef, policy: AbstractPolicy, cache: ConcurrentAttributeCache, db: AttributeDatabaseConnection,
  enableStatsDb: Boolean = false, mockEvaluation: Boolean = false,
  mockEvaluationDuration: Int = 0) extends Actor with ActorLogging {

  // TODO use attribute cache here if necessary
  val finder = new AttributeFinder
  finder += new DatabaseAttributeFinderModule(db, enableStatsDb)
  finder += new HardcodedEnvironmentAttributeFinderModule
  val pdp = new PDP(policy, finder)
  
  /**
   * Close the connection when we are destroyed.
   */
  override def postStop() = {
    db.close
    log.debug("Closed the AttributeDatabaseConnection")
  }

  import ForemanWorkerProtocol._

  /**
   *  This is the state we're in when we're working on something.
   *  In this state we can deal with messages in a much more
   *  reasonable manner.
   */
  def working(work: Any): Receive = {
    case WorkIsReady => // Pass... we're already working
    case NoWorkToBeDone => // Pass... we're already working
    case WorkToBeDone(_, _) => // Pass... we shouldn't even get this 
      log.error("Yikes. Master told me to do work, while I'm working.")
    case x => log.error(s"Unknown message received: $x")
  }

  /**
   *  In this state we have no work to do.  There really are only
   *  two messages that make sense while we're in this state, and
   *  we deal with them specially here.
   */
  def idle: Receive = {
    case WorkIsReady => // Coordinator says there's work to be done, let's ask for it
      log.debug("Requesting work")
      foreman ! WorkerRequestsWork(self)
    case WorkToBeDone(request, coordinator) => // Send the work off to the implementation
      Tracer.trace(request.id, "Worker", "process") {
        log.debug(s"[Evaluation ${request.id}] Evaluating $request for $coordinator")
        context.become(working((request, coordinator))) // NOTE: this does not mean anything, 
        // since the evaluation is synchronous and the the other requests are 
        // queued up in the message queue of this actor.
        // As a result, this worker WILL currently be assigned multiple requests.
        processRequest(request, coordinator)
      }
    case NoWorkToBeDone => // We asked for work, but either someone else got it first, or
    // there's literally no work to be done
    case x => log.error(s"Unknown message received: $x")
  }

  def receive = idle

  /**
   *
   */
  private def processRequest(request: PolicyEvaluationRequest, coordinator: ActorRef): Unit = {
    // the mock result
    var result = new Result(Deny)
    if (mockEvaluation) {
      // If we should mock, wait for the given duration
      if (mockEvaluationDuration > 0) {
        blocking {
          Thread.sleep(mockEvaluationDuration)
        }
      }
    } else {
      // If we should not mock, do an actual evaluation
      // TODO implement evaluating the policy asked for by the request
      result = pdp.evaluate(request.subjectId, request.actionId, request.resourceId, request.extraAttributes: _*)
      db.commit()
    }
    // pass the decision directly to the coordinator...
    coordinator ! CoordinatorForemanProtocol.PolicyEvaluationResult(request.id, result)
    // ... and request new work from the foreman...
    foreman ! WorkerIsDoneAndRequestsWork(self)
    // ... and change our mode
    context.become(idle)
    log.debug(s"[Evaluation ${request.id}] Evaluated $request, result is $result")
  }
}