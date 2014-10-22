package stapl.distribution.test.actors.test

import akka.actor.ActorPath
import akka.actor.ActorLogging
import akka.actor.Actor
import akka.actor.ActorRef
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import akka.actor.Props
import scala.concurrent.duration._
import scala.util.{ Success, Failure }

/**
 * Abstract super class for all workers. A worker interacts with a work manager
 * that queues and distributes the work. 
 */
abstract class Worker(coordinator: ActorRef)
  extends Actor with ActorLogging {
  import CoordinatorWorkerProtocol._

  /**
   *  This is how our derivations will interact with us.  It
   *  allows derivations to complete work asynchronously
   */
  case class WorkComplete(result: Any)

  /**
   *  Required to be implemented
   */
  def doWork(workSender: ActorRef, work: Any): Unit

  /**
   *  Notify the Master that we're alive
   */
  override def preStart() = coordinator ! WorkerCreated(self)

  /**
   *  This is the state we're in when we're working on something.
   *  In this state we can deal with messages in a much more
   *  reasonable manner.
   */
  def working(work: Any): Receive = {
    case WorkIsReady => // Pass... we're already working
    case NoWorkToBeDone => // Pass... we're already working
    case WorkToBeDone(_) => // Pass... we shouldn't even get this 
      log.error("Yikes. Master told me to do work, while I'm working.")
    case WorkComplete(result) => // Our derivation has completed its task
      log.debug("Work is complete.  Result {}.", result)
      coordinator ! WorkerIsDoneAndRequestsWork(self) // TODO kan dit niet samengevoegd worden?
      // We're idle now
      context.become(idle)
  }

  /**
   *  In this state we have no work to do.  There really are only
   *  two messages that make sense while we're in this state, and
   *  we deal with them specially here.
   */
  def idle: Receive = {
    case WorkIsReady => // Coordinator says there's work to be done, let's ask for it
      log.debug("Requesting work")
      coordinator ! WorkerRequestsWork(self)
    case WorkToBeDone(work) => // Send the work off to the implementation
      log.debug("Got work {}", work)
      doWork(sender, work)
      context.become(working(work))
    case NoWorkToBeDone => // We asked for it, but either someone else got it first, or
    // there's literally no work to be done
  }

  def receive = idle
}