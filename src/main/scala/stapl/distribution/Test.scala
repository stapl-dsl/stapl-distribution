package stapl.distribution

import akka.actor.Actor
import scala.concurrent.duration._
import akka.actor.actorRef2Scala
import scala.math.BigInt.int2bigInt
import stapl.distribution.util.Timer
import akka.actor.ActorSystem
import akka.actor.Props
import stapl.distribution.db.AttributeDatabaseConnection
import stapl.distribution.db.entities.EntityManager
import stapl.core.pdp.AttributeFinder
import stapl.distribution.db.DatabaseAttributeFinderModule
import stapl.examples.policies.EhealthPolicy
import stapl.core.Result
import stapl.core.ConcreteValue
import stapl.core.Attribute
import stapl.core.pdp.PDP

object Test extends App {

//    val system = ActorSystem("test")
//    implicit val dispatcher = system.dispatcher
////    implicit val timeout = Timeout(2 seconds)
//  
//    val actor = system.actorOf(Props[Actor2])
//    
//    actor ! "ping"
//    actor ! "pang"

//    val f = actor ? "ping"
//    val decision: Decision = Await.ready(f, 3 seconds).value match {
//      case None =>
//        // should never happen, but just in case...
//        println("WTF: None received from ping???")
//        Deny
//      case Some(result) => result match {
//        case Success("pong") =>
//          println("pong received")
//          Permit
//        case Success(x) =>
//          println(s"wft, received: $x")
//          Deny
//        case Failure(e: AskTimeoutException) =>
//          println("Timeout => default deny")
//          Deny
//        case Failure(e) =>
//          println("Another failure? => default deny anyway")
//          Deny
//      }
//    }
//    println(s"Decision = $decision")

//    system.shutdown
}

class TestActor extends Actor {

  def receive = {
    case "ping" => println("ping")
  }
}

class Actor2 extends TestActor {
  
  override def receive = super.receive orElse {
    case "pang" => println("pang")
  }
}