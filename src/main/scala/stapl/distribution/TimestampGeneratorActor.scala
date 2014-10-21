package stapl.distribution

import akka.actor.Actor
import akka.actor.ActorLogging
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

