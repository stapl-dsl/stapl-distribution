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

sealed trait TimestampGeneratorProtocol
case class GetTimestamp
case class Timestamp(timestamp: Long)

/**
 * The Scala actor that wraps a timestamp generator for thread-safe concurrent
 * usage.
 */
class TimestampGeneratorActor extends Actor with ActorLogging {

  val generator = new SimpleTimestampGenerator

  def receive = {
    case GetTimestamp => sender ! Timestamp(generator.getTimestamp)
    case msg: Any => log.warning(s"Unknown message received: $msg")
  }
}

