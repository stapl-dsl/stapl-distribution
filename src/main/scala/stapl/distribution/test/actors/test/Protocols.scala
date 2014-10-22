package stapl.distribution.test.actors.test

import akka.actor.ActorRef
import stapl.core.Decision
import stapl.core.Result

/**
 * For communicating with clients (mainly for testing purposes) 
 */
object ClientProtocol {
  case class Go
}

/**
 * For communication between clients and the coordinator
 */
object ClientCoordinatorProtocol {
  case class AuthorizationRequest(subjectId: String, actionId: String, resourceId: String)
  case class AuthorizationDecision(decision: Decision)
}

/**
 * For communication between the coordinator and workers
 */
sealed abstract class PolicyToBeEvaluated
case object Top extends PolicyToBeEvaluated
case class ById(id: String) extends PolicyToBeEvaluated

case class PolicyEvaluationRequest(id: Int, policy: PolicyToBeEvaluated, subjectId: String, actionId: String, resourceId: String)

object CoordinatorWorkerProtocol {  
  // Messages from Workers
  case class WorkerCreated(worker: ActorRef)
  case class WorkerRequestsWork(worker: ActorRef)
  case class WorkIsDone(worker: ActorRef)
  case class PolicyEvaluationResult(id: Int, result: Result)

  // Messages to Workers
  case class WorkToBeDone(work: List[PolicyEvaluationRequest])
  case object WorkIsReady
  case object NoWorkToBeDone
}