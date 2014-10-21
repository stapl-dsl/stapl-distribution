package stapl.distribution.test.actors.test

import akka.actor.ActorPath
import akka.actor.ActorRef
import akka.dispatch.MessageDispatcher
import scala.concurrent.Future
import akka.pattern.pipe

class TestWorker(masterLocation: ActorPath) extends Worker(masterLocation) {
  // We'll use the current dispatcher for the execution context.
  // You can use whatever you want.
  implicit val dispatcher = context.dispatcher
 
  def doWork(workSender: ActorRef, msg: Any): Unit = {
    Future {
      workSender ! msg
      WorkComplete("done")
    } pipeTo self
  }
}