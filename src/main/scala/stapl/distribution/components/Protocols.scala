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
import stapl.core.Decision
import stapl.core.Result
import stapl.core.Attribute
import stapl.core.ConcreteValue
import stapl.core.AttributeContainerType
import stapl.distribution.util.TraceStep
import stapl.distribution.util.Trace

/**
 * For communicating with clients (mainly for testing purposes)
 */
object ClientProtocol {
  case class Go(nbRequests: Int)
}

/**
 * For communication between clients and the coordinator.
 */
object ClientCoordinatorProtocol {
  case class AuthorizationRequest(subjectId: String, actionId: String, resourceId: String,
    extraAttributes: List[(Attribute, ConcreteValue)] = List())
  case class AuthorizationDecision(evaluationId: String, decision: Decision)
}

/**
 * For communication between the coordinator and foremen.
 */
sealed abstract class PolicyToBeEvaluated
case object Top extends PolicyToBeEvaluated
case class ById(id: String) extends PolicyToBeEvaluated

case class PolicyEvaluationRequest(id: String, policy: PolicyToBeEvaluated, subjectId: String,
  actionId: String, resourceId: String, extraAttributes: List[(Attribute, ConcreteValue)])

object InternalCoordinatorProtocol {
  case class Enqueue(request: PolicyEvaluationRequest, theResultShouldGoTo: ActorRef)
}

object CoordinatorForemanProtocol {
  // Messages from Foremen
  case class ForemanCreated(foreman: ActorRef)
  case class ForemanRequestsWork(foreman: ActorRef, nbRequests: Int)
  case class ForemanIsDoneAndRequestsWork(foreman: ActorRef, nbRequests: Int)
  case class PolicyEvaluationResult(id: String, result: Result) // this will actually be sent by a worker

  // Messages to Foremen
  case class WorkToBeDone(work: List[(PolicyEvaluationRequest, ActorRef)])
  case object WorkIsReady
}

/**
 * For communication between concurrent coordinators.
 */
object ConcurrentCoordinatorProtocol {
  case class ManageResourceAndStartEvaluation(originalRequestWithAppropriateAttributesAdded: PolicyEvaluationRequest)
  case class ManageResourceAndRestartEvaluation(originalRequestWithAppropriateAttributesAdded: PolicyEvaluationRequest)
  case class TryCommitForResource(result: CoordinatorForemanProtocol.PolicyEvaluationResult)
  case class CommitForResourceFailed(result: CoordinatorForemanProtocol.PolicyEvaluationResult)
  case class CommitForResourceSucceeded(result: CoordinatorForemanProtocol.PolicyEvaluationResult)
}

/**
 * For communication between the foremen and their workers.
 */
object ForemanWorkerProtocol {
  // Messages from workers
  case class WorkerRequestsWork(worker: ActorRef)
  case class WorkerIsDoneAndRequestsWork(worker: ActorRef)

  // Messages to workers
  case class WorkToBeDone(work: PolicyEvaluationRequest, coordinator: ActorRef)
  case object WorkIsReady
  case object NoWorkToBeDone
}

/**
 * For communication with ConcurrentCoordinatorManagers
 */
object ConcurrentCoordinatorManagerProtocol {
  case class GetCoordinators
  // Note: it is very important that the order of the Coordinators is maintained
  // because this order leads to the distribution of requests and this distribution
  // should be the same on every node.
  case class Coordinators(coordinators: Seq[ActorRef])
}

/**
 * For communication between DistributedCoordinators.
 */
object DistributedCoordinatorRegistrationProtocol {
  
  /**
   * The sender is the coordinator that registers himself.
   */
  case class Register(coordinator: ActorRef)
  
  /**
   * @param yourId
   * 		The id of the newly regsitered coordinator.
   * @param	coordinators	
   * 		The list of all coordinators as ActorRef and their id. This list includes 
   *   		the newly registered coordinator.
   * 
   * Note: it is very important that the order of the Coordinators is maintained
   * because this order leads to the distribution of requests and this distribution
   * should be the same on every node.
   */  
  case class AckOfRegister(yourId: Int, coordinators: List[(Int,ActorRef)]) 
  
  /**
   * 
   */
  case class ListOfCoordinatorsWasUpdated(coordinators: List[(Int,ActorRef)]) 
}
object ClientRegistrationProtocol {
  
  /**
   * 
   */
  case class GetListOfCoordinators(client: ActorRef)
  
  /**
   * @param	coordinators	
   * 		The list of all coordinators as ActorRef and their id. This list includes 
   *   		the newly registered coordinator.
   * 
   * Note: it is very important that the order of the Coordinators is maintained
   * because this order leads to the distribution of requests and this distribution
   * should be the same on every node.
   */  
  case class ListOfCoordinators(coordinators: List[(Int,ActorRef)])
}

/**
 * For configuring a DistributedCoordinatorManager
 */
object DistributedCoordinatorConfigurationProtocol {  
  
  /**
   * Sets the number of coordinators to be used. Will result in  
   * a SetNumberCoordinatorsSuccess or SetNumberCoordinatorsFailed back.
   * The update will fail if there are not enough coordinators present.
   */
  case class SetNumberCoordinators(nb: Int)
  
  /**
   * The number of coordinators to be used was successfully updated.
   */
  case class SetNumberCoordinatorsSuccess
  
  /**
   * The number of coordinators to be used could not be updated.
   * 
   * @param msg	
   * 		An error message.
   */
  case class SetNumberCoordinatorsFailed(msg: String)
}

/**
 * For collecting traces.
 */
object TraceProtocol {
  
  /**
   * Requests the trace for a single policy evaluation.
   */
  case class GetTrace(policyEvaluationId: String)
  
  /**
   * For sending back the trace to the actor that requested it.
   */
  case class Trace(steps: List[TraceStep])
}
