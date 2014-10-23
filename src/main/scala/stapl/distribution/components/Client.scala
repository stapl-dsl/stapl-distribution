package stapl.distribution.components

import akka.actor.ActorLogging
import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.Props
import scala.concurrent.duration._
import akka.actor.ActorRef
import com.typesafe.config.ConfigFactory
import scala.util.{ Success, Failure }
import akka.util.Timeout
import scala.concurrent.Await
import stapl.core.Decision
import stapl.core.Deny
import akka.pattern.AskTimeoutException
import akka.routing.BroadcastPool
import akka.actor.actorRef2Scala
import stapl.distribution.util.Timer
import akka.pattern.ask
import util.control.Breaks._

class Client(coordinator: ActorRef) extends Actor with ActorLogging {

  import ClientProtocol._
  import ClientCoordinatorProtocol._

  implicit val timeout = Timeout(2.second)
  implicit val ec = context.dispatcher

  def receive = {
    case Go(nbRuns) => // launch a test of 100 messages 
      val timer = new Timer
      var counter = 0
      breakable {
        while (true) {
          //for (i <- 0 to nbRuns) {
          timer.time {
            val f = coordinator ? AuthorizationRequest(s"subject-of-client", "view", "doc123")
            val decision: Decision = Await.ready(f, 3 seconds).value match {
              case None =>
                // should never happen, but just in case...
                println("WTF: None received from ping???")
                Deny
              case Some(result) => result match {
                case Success(AuthorizationDecision(decision)) =>
                  log.debug(s"Result of policy evaluation was: $decision")
                  decision
                case Success(x) =>
                  log.warning(s"No idea what I received here: $x => default deny")
                  break
                  Deny
                case Failure(e: AskTimeoutException) =>
                  log.warning("Timeout => default deny")
                  break
                  Deny
                case Failure(e) =>
                  log.warning("Some other failure? => default deny anyway")
                  break
                  Deny
              }
            }
          }
          counter += 1
          if (counter % 100 == 0) {
            println(s"Mean latency of the last 100 requests: ${timer.mean}ms")
            timer.reset
          }
        }
      }
      log.info(s"Client finished: $nbRuns requests in ${timer.total} ms (mean: ${timer.mean} ms)")
    case msg => log.error(s"Received unknown message: $msg")
  }

  log.info(s"Client created: $this")
}