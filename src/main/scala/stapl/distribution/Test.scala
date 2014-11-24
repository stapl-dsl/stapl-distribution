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
import scala.util.{Try, Success, Failure}

object Test extends App {

  def test = {
    import concurrent.Future
    import concurrent.ExecutionContext.Implicits.global

    val f1: Future[String] = Future successful ("f1")
    f1 map {
      _ => println(s"f1 finished on Thead ${Thread.currentThread().getId()}")
    }
    val f2: Future[String] = f1.value match {
      // Not completed: 
      case None => f1
      // Completed:
      case Some(x) => x match { 
        case Failure(e) => Future failed e
        case Success(s) => Future successful s
      }
    }
    println("after futures")
    println("====================")
  }
  
  for(i <- 1 to 1000) {
    test
  }
  
  

//  def test = {
//    import concurrent.Future
//    import concurrent.ExecutionContext.Implicits.global
//
//    val f1: Future[String] = Future successful ("f1")
//    f1 map {
//      _ => println(s"f1 finished on Thead ${Thread.currentThread().getId()}")
//    }
//    if (f1.isCompleted) {
//      f1.value map {
//        _ map {
//          x => println(s"f1 finished on Thead ${Thread.currentThread().getId()}")
//        }
//      }
//    }
//    println("after futures")
//    println("====================")
//  }
//  
//  for(i <- 1 to 1000) {
//    test
//  }

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