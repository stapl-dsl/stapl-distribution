package stapl.distribution.util

import akka.actor.Actor

/**
 * Duration in ms
 */
case class EvaluationEnded(duration: Double = -1)

/**
 * An actor that wraps a ThroughputAndLatencyStatistics object.
 */
class StatisticsActor(name: String, intervalSize: Int, nbIntervals: Int) extends Actor {

  val stats = new ThroughputAndLatencyStatistics(name, intervalSize, nbIntervals)  
  
  def receive = {

    /**
     * Duration in ms
     */
    case EvaluationEnded(duration) => stats.tick(duration)
  }
}