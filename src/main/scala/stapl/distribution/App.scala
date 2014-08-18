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
import stapl.distribution.cache.AttributeCache

/**
 * @author ${user.name}
 */
object App {

  def main(args: Array[String]) {
    //resetDB

    testSingle
  }
  
  def resetDB {
    import stapl.core.examples.EhealthPolicy.{ subject, action, resource, naturalPolicy => policy }

    // set up db    
    val db = new AttributeDatabaseConnection("localhost", 3306, "stapl-attributes", "root", "root")
    db.open
    db.cleanStart
    db.storeAttribute("maarten", subject.get("roles"), List("medical_personnel", "physician")) // FIXME WTF, no idea why "subject.roles" gives the error "errornous or inaccessible type"
    db.commit
    db.close    
  }

  def testActors {
    import stapl.core.examples.EhealthPolicy.{ subject, action, resource, naturalPolicy => policy }

    val system = ActorSystem("Barista")

    val router = system.actorOf(RoundRobinPool(5).props(Props(classOf[PDPActor], policy)), "router")

    val ctx = new RequestCtx("maarten", "view", "doc123",
      // leave out the roles to test the database
      //subject.roles -> List("medical_personnel", "physician"),
      subject.triggered_breaking_glass -> false,
      subject.department -> "cardiology",
      resource.type_ -> "patientstatus",
      resource.owner_withdrawn_consents -> List("subject1", "subject2", "subject3"),
      resource.operator_triggered_emergency -> false,
      resource.indicates_emergency -> true)

    implicit val timeout = Timeout(2.second)
    implicit val executionContext = system.dispatcher

    for (i <- 1 to 100) {
      val result: Future[Any] = router ? Evaluate(f"$i", ctx)
      result.onSuccess {
        case EvaluationResult(policyId, result) => println(s"Result for policyId $policyId was $result")
      }
    }

    //system.shutdown()
  }

  def testSingle {
    import stapl.core.examples.EhealthPolicy.{ subject, action, resource, naturalPolicy => policy }

    val db = new AttributeDatabaseConnection("localhost", 3306, "stapl-attributes", "root", "root")
    db.open
    
    val cache = new AttributeCache

    val finder = new AttributeFinder
    finder += new DatabaseAttributeFinderModule(db, cache)
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

    val result = pdp.evaluate(123, ctx)
    println(result)
  }

}

sealed trait PolicyEvaluationProtocol
case class Evaluate(policyId: String, ctx: RequestCtx) extends PolicyEvaluationProtocol
object Evaluate {
  def apply(policyId: String, subjectId: String, actionId: String, resourceId: String,
    extraAttributes: (Attribute, ConcreteValue)*): Evaluate = {
    val ctx = new RequestCtx(subjectId, actionId, resourceId, extraAttributes: _*)
    Evaluate(policyId, ctx)
  }
}
case class EvaluationResult(policyId: String, result: Result) extends PolicyEvaluationProtocol

/**
 * The Scala actor that wraps a PDP and is able to evaluate policies on request.
 */
class PDPActor(policy: AbstractPolicy) extends Actor with ActorLogging {

  val db = new AttributeDatabaseConnection("localhost", 3306, "stapl-attributes", "root", "root")
  db.open

  val finder = new AttributeFinder
  finder += new DatabaseAttributeFinderModule(db, null) // TODO fixme
  val pdp = new PDP(policy, finder)

  def receive = {
    case Evaluate(policyId, ctx) => {
      //log.info(f"Worker $this processed $ctx");
      val result = pdp.evaluate(ctx)
      sender ! EvaluationResult(policyId, result)
    }
  }
}

