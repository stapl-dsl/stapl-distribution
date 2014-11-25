package stapl.distribution

import akka.actor.Actor
import scala.concurrent.duration._
import akka.actor.actorRef2Scala
import scala.math.BigInt.int2bigInt
import stapl.distribution.util.Timer
import akka.actor.ActorSystem
import akka.actor.Props
import stapl.distribution.db.AttributeDatabaseConnection
import stapl.distribution.db.entities.ehealth.EntityManager
import stapl.core.pdp.AttributeFinder
import stapl.distribution.db.DatabaseAttributeFinderModule
import stapl.examples.policies.EhealthPolicy
import stapl.core.Result
import stapl.core.ConcreteValue
import stapl.core.Attribute
import stapl.core.pdp.PDP
import scala.util.{ Try, Success, Failure }
import stapl.distribution.components.Coordinator
import stapl.distribution.components.SimpleForeman
import stapl.distribution.components.CoordinatorForemanProtocol
import stapl.distribution.components.PolicyEvaluationRequest
import stapl.distribution.components.Top
import stapl.distribution.components.ClientCoordinatorProtocol
import akka.pattern.ask
import akka.util.Timeout
import stapl.core.Decision
import grizzled.slf4j.Logging
import scala.actors.AskTimeoutException

object Test extends App with Logging {

  //  def test = {
  //    import concurrent.Future
  //    import concurrent.ExecutionContext.Implicits.global
  //
  //    val f1: Future[String] = Future successful ("f1")
  //    f1 map {
  //      _ => println(s"f1 finished on Thead ${Thread.currentThread().getId()}")
  //    }
  //    val f2: Future[String] = f1.value match {
  //      // Not completed: 
  //      case None => f1
  //      // Completed:
  //      case Some(x) => x match { 
  //        case Failure(e) => Future failed e
  //        case Success(s) => Future successful s
  //      }
  //    }
  //    println("after futures")
  //    println("====================")
  //  }
  //  
  //  for(i <- 1 to 1000) {
  //    test
  //  }

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

  val system = ActorSystem("test")
  implicit val dispatcher = system.dispatcher
  implicit val timeout = Timeout(200 seconds)

  val coordinator = system.actorOf(Props[Coordinator], "coordinator")
  val foreman = system.actorOf(Props(classOf[SimpleForeman], coordinator, 50, EhealthPolicy.naturalPolicy), "foreman")

  val em = EntityManager()

  //  val f = foreman ! CoordinatorForemanProtocol.WorkToBeDone(List(
  //    new PolicyEvaluationRequest(1, Top, em.maarten.id, "view", em.maartenStatus.id),
  //    new PolicyEvaluationRequest(2, Top, em.maarten.id, "view", em.maartenStatus.id),
  //    new PolicyEvaluationRequest(3, Top, em.maarten.id, "view", em.maartenStatus.id)))

  def test() = {
    val f = coordinator ? ClientCoordinatorProtocol.AuthorizationRequest(em.maarten.id, "view", em.maartenStatus.id)
    f onComplete {
      _ match {
        case Success(ClientCoordinatorProtocol.AuthorizationDecision(decision)) =>
          //info(s"Result of policy evaluation was: $decision")
        case Success(x) =>
          error(s"No idea what I received here: $x => default deny")
        case Failure(e: AskTimeoutException) =>
          error("Timeout => default deny")
        case Failure(e) =>
          error(s"Some other failure: $e => default deny anyway")
      }
    }
  }
  for(i <- 1 to 100000) {
    test
  }

  //system.shutdown
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