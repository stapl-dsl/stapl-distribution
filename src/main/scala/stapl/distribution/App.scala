package stapl.distribution

import akka.actor.ActorSystem
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Actor
import akka.routing.RoundRobinPool
import akka.actor.ActorLogging
import akka.pattern.ask
import stapl.core.pdp.EvaluationCtx
import stapl.core.pdp.BasicEvaluationCtx
import stapl.core.pdp.RequestCtx
import stapl.core.Attribute
import stapl.core.ConcreteValue
import stapl.core.AbstractPolicy
import stapl.core.pdp.PDP
import stapl.core.Result
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Future
import stapl.core.pdp.AttributeFinder
import stapl.core.pdp.AttributeFinderModule
import stapl.core.AttributeContainerType
import stapl.core.AttributeType
import stapl.core.SUBJECT
import stapl.core.RESOURCE
import stapl.core.ENVIRONMENT
import org.joda.time.LocalDateTime
import stapl.distribution.db.DatabaseAttributeFinderModule
import stapl.distribution.db.AttributeDatabaseConnection
import stapl.core.pdp.TimestampGenerator
import stapl.core.pdp.SimpleTimestampGenerator
import stapl.distribution.concurrency.LocalConcurrentAttributeCache
import stapl.distribution.db.LegacyAttributeDatabaseConnection

/**
 * @author ${user.name}
 */
object App {

  /**
   * Arguments:
   * - First argument: "centralized" | "distributed"
   * - Second argument: the number of local threads
   * - If distributed: next arguments are the IP addresses of the other clients in the cluster
   */
  def main(args: Array[String]) {
    if(args(0) == "centralized") {
      val nbThreads = args(1).toInt
      //nbTreahd
    } else if(args(0) == "distributed") {
      val nbThreads = args(1).toInt
      
    } else {
      throw new IllegalArgumentException("the first argument should be \"centralized\" or \"distributed\"")
    }
    
    //resetDB

    //testSingle
    testActors
  }

  def resetDB {
    import stapl.examples.policies.EhealthPolicy.{ subject, action, resource, naturalPolicy => policy }

    // set up db    
    val db = new LegacyAttributeDatabaseConnection("localhost", 3306, "stapl-attributes", "root", "root")
    db.open
    db.cleanStart
    db.storeAttribute("maarten", subject.get("roles"), List("medical_personnel", "physician")) // FIXME WTF, no idea why "subject.roles" gives the error "errornous or inaccessible type"
    db.commit
    db.close
  }

  def testActors {
    import stapl.examples.policies.EhealthPolicy.{ subject, action, resource, naturalPolicy => policy }

    val system = ActorSystem("Barista")

    val cache = new LocalConcurrentAttributeCache

//    val timestampGenerator = system.actorOf(Props(classOf[TimestampGeneratorActor]))
//
//    val router = system.actorOf(RoundRobinPool(5).props(Props(classOf[PolicyEvaluationActor], policy, cache, timestampGenerator)), "router")
//
//    val ctx = new RequestCtx("maarten", "view", "doc123",
//      // leave out the roles to test the database
//      //subject.roles -> List("medical_personnel", "physician"),
//      subject.triggered_breaking_glass -> false,
//      subject.department -> "cardiology",
//      resource.type_ -> "patientstatus",
//      resource.owner_withdrawn_consents -> List("subject1", "subject2", "subject3"),
//      resource.operator_triggered_emergency -> false,
//      resource.indicates_emergency -> true)
//
//    implicit val timeout = Timeout(2.second)
//    implicit val executionContext = system.dispatcher
//
//    for (i <- 1 to 100) {
//      val result: Future[Any] = router ? Evaluate(f"$i", ctx)
//      result.onSuccess {
//        case EvaluationResult(policyId, result) => println(s"Result for policyId $policyId was $result")
//      }
//    }

    //system.shutdown()
  }

  def testSingle {
    import stapl.examples.policies.EhealthPolicy.{ subject, action, resource, naturalPolicy => policy }

    val db = new LegacyAttributeDatabaseConnection("localhost", 3306, "stapl-attributes", "root", "root")
    db.open

    val finder = new AttributeFinder
    finder += new DatabaseAttributeFinderModule(db)
    val pdp = new PDP(policy, finder)

    val ctx = new RequestCtx("maarten", "view", "doc123",
      // leave out the roles to test the database
      //subject.roles -> List("medical_personnel", "physician"),
      subject.triggered_breaking_glass -> false,
      subject.department -> "cardiology",
      resource.type_ -> "patientstatus",
      resource.owner_withdrawn_consents -> List("subject1", "subject2", "subject3"),
      resource.operator_triggered_emergency -> false,
      resource.indicates_emergency -> true)

    val result = pdp.evaluate(ctx)
    println(result)
  }
}