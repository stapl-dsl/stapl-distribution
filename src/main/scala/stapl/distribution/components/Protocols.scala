package stapl.distribution.components

import akka.actor.ActorRef
import stapl.core.Decision
import stapl.core.Result
import stapl.core.Attribute
import stapl.core.ConcreteValue

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
  case class AuthorizationRequest(subjectId: String, actionId: String, resourceId: String, extraAttributes: List[(Attribute, ConcreteValue)] = List())
  case class AuthorizationDecision(decision: Decision)
}

/**
 * For communication between the coordinator and foremen.
 */
sealed abstract class PolicyToBeEvaluated
case object Top extends PolicyToBeEvaluated
case class ById(id: String) extends PolicyToBeEvaluated

case class PolicyEvaluationRequest(id: Int, policy: PolicyToBeEvaluated, subjectId: String, 
    actionId: String, resourceId: String, extraAttributes: List[(Attribute, ConcreteValue)])

object CoordinatorForemanProtocol {  
  // Messages from Foremen
  case class ForemanCreated(foreman: ActorRef)
  case class ForemanRequestsWork(foreman: ActorRef, nbRequests: Int)
  case class ForemanIsDoneAndRequestsWork(foreman: ActorRef, nbRequests: Int)
  case class PolicyEvaluationResult(id: Int, result: Result) // this will actually be sent by a worker

  // Messages to Foremen
  case class WorkToBeDone(work: List[PolicyEvaluationRequest])
  case object WorkIsReady
}

/**
 * For communication between the foremen and their workers.
 */
object ForemanWorkerProtocol {
  // Messages from workers
  case class WorkerRequestsWork(worker: ActorRef)
  case class WorkerIsDoneAndRequestsWork(worker: ActorRef)

  // Messages to workers
  case class WorkToBeDone(work: PolicyEvaluationRequest)
  case object WorkIsReady
  case object NoWorkToBeDone  
}