package stapl.distribution.components

import akka.actor.ActorRef
import stapl.core.Decision
import stapl.core.Result
import stapl.core.Attribute
import stapl.core.ConcreteValue
import stapl.core.AttributeContainerType

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
  case class AuthorizationDecision(decision: Decision)
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
  case class Enqueue(request: PolicyEvaluationRequest)
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
  case class StartRequestAndManageSubject(sendingCoordinator: ActorRef, client: ActorRef,
    original: PolicyEvaluationRequest, withAppropriateAttributes: PolicyEvaluationRequest)
  case class StartRequestAndManageResource(sendingCoordinator: ActorRef, client: ActorRef,
    original: PolicyEvaluationRequest, withAppropriateAttributes: PolicyEvaluationRequest)
  case class RestartRequestAndManageSubject(sendingCoordinator: ActorRef, client: ActorRef,
    original: PolicyEvaluationRequest, withAppropriateAttributes: PolicyEvaluationRequest)
  case class RestartRequestAndManageResource(sendingCoordinator: ActorRef, client: ActorRef,
    original: PolicyEvaluationRequest, withAppropriateAttributes: PolicyEvaluationRequest)
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
 * For communication with ConcurrentActorManagers
 */
object ConcurrentActorManagerProtocol {
  case class GetCoordinators
  // Note: it is very important that the order of the Coordinators is maintained
  // because this order leads to the distribution of requests and this distribution
  // should be the same on every node.
  case class Coordinators(coordinators: Seq[ActorRef])
}
