package stapl.distribution.test.actors.test

import akka.actor.ActorPath
import akka.actor.ActorRef
import akka.dispatch.MessageDispatcher
import scala.concurrent.Future
import akka.pattern.pipe
import stapl.core.Permit
import stapl.core.Result

class Foreman(coordinator: ActorRef) extends Worker(coordinator) {

  import CoordinatorWorkerProtocol._

  // We'll use the current dispatcher for the execution context.
  // You can use whatever you want.
  implicit val dispatcher = context.dispatcher

  /**
   * Evaluate the policy and notify the Coordinator of the result
   *
   * Note that this implementation actually does not need the workSender.
   */
  def doWork(workSender: ActorRef, work: Any): Unit = {
    // the work of this worker should always be a list of policy evaluation requests
    val requests = work.asInstanceOf[List[PolicyEvaluationRequest]]
    
    for (request <- requests) {
      // TODO insert actual policy evaluation here
      // TODO forward each request to a worker
      coordinator ! PolicyEvaluationResult(request.id, Result(Permit)) 
    }
    
    self ! WorkComplete("done")
  }
}