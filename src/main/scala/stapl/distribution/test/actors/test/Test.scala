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

object Test extends App {
  
  val system = ActorSystem("test")
	implicit val dispatcher = system.dispatcher
	implicit val timeout = Timeout(2 seconds)
  
  val actor = system.actorOf(Props[TestActor])
  
  val f = actor ? "ping"
  f onComplete {
    case Success("pong") => println("pong received")
    case Success(x) => println(s"wft, received: $x")
    case Failure(t) => t.printStackTrace()
  }
}

class TestActor extends Actor {
  
  def receive = {
    case "ping" => sender ! "pong" 
  }
}