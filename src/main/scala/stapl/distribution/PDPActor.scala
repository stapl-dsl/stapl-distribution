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
import stapl.core.pdp.TimestampGenerator
import stapl.core.pdp.SimpleTimestampGenerator

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
class PDPActor(policy: AbstractPolicy, cache: AttributeCache, timestampGenerator: ActorRef) extends Actor with ActorLogging {

  val db = new AttributeDatabaseConnection("localhost", 3306, "stapl-attributes", "root", "root")
  db.open

  val finder = new AttributeFinder
  finder += new DatabaseAttributeFinderModule(db, cache) // TODO fixme
  val pdp = new PDP(policy, finder)

  implicit val timeout = Timeout(2.second)
  import context.dispatcher // the implicit executor for futures

  def receive = {
    case Evaluate(policyId, ctx) => {
      //log.info(f"Worker $this processed $ctx");
      val timestamp: Future[Any] = timestampGenerator ? GetTimestamp
      val s = sender // store the original sender for the response
      timestamp.onSuccess {
        case Timestamp(t) => {
	        val result = pdp.evaluate(t, ctx)
	        s ! EvaluationResult(policyId, result)
        }
      }
    }
  }
}