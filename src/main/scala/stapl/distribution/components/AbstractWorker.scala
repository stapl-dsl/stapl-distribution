package stapl.distribution.components

import akka.actor.ActorLogging
import akka.actor.Actor
import akka.actor.ActorRef
import scala.concurrent.duration._

/**
 * Abstract super class for all workers. A worker interacts with a work manager
 * that queues and distributes the work. 
 */
abstract class AbstractWorker(coordinator: ActorRef)
  extends Actor with ActorLogging {
  
  
}