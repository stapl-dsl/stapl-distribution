package stapl.distribution.test.actors.test

import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.ActorPath
import akka.actor.ActorRef
import akka.actor.Actor
import scala.util.{ Success, Failure }
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.Await
import stapl.core.Decision
import stapl.core.Deny
import stapl.core.Permit
import akka.pattern.AskTimeoutException

object Test extends App {

  val timer = new Timer
  for (i <- 0 until 1000) {
    timer.time {
    	var factorial: BigInt = 0
    	for(i <- 0 until 10000) {
    	  factorial *= i
    	}
    }
  }
  println(f"${timer.mean}%2.2f ms")

  //  val system = ActorSystem("test")
  //  implicit val dispatcher = system.dispatcher
  //  implicit val timeout = Timeout(2 seconds)
  //
  //  val actor = system.actorOf(Props[TestActor])
  //
  //  val f = actor ? "ping"
  //  val decision: Decision = Await.ready(f, 3 seconds).value match {
  //    case None =>
  //      // should never happen, but just in case...
  //      println("WTF: None received from ping???")
  //      Deny
  //    case Some(result) => result match {
  //      case Success("pong") =>
  //        println("pong received")
  //        Permit
  //      case Success(x) =>
  //        println(s"wft, received: $x")
  //        Deny
  //      case Failure(e: AskTimeoutException) =>
  //        println("Timeout => default deny")
  //        Deny
  //      case Failure(e) =>
  //        println("Another failure? => default deny anyway")
  //        Deny
  //    }
  //  }
  //  println(s"Decision = $decision")
  //
  //  system.shutdown
}

class TestActor extends Actor {

  def receive = {
    case "ping" => sender ! "pong"
  }
}