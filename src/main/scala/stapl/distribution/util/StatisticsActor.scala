package stapl.distribution.util

import akka.actor.Actor

/**
 * Duration in ms
 */
case class EvaluationEnded(duration: Double = -1)

class StatisticsActor(intervalSize: Int, nbIntervals: Int) extends Actor {  

  import scala.collection.mutable.ListBuffer

  var durationsLastInterval = ListBuffer[Double]()
  val durationsLastIntervals = new Intervals(nbIntervals)  
  val throughputLastIntervals = new Intervals(nbIntervals)
  val durationAllIntervals = new Intervals()
  val throughputAllIntervals = new Intervals()

  def totalCount = durationsLastInterval.size * intervalSize + durationsLastInterval.size
  def intervalCount = durationsLastInterval.size

  val totalStart = System.nanoTime()
  var intervalStart = System.nanoTime()

  /**
   * The duration in ms
   */
  def totalDuration = {
    val now = System.nanoTime()
    (now.toDouble - totalStart.toDouble) / 1000000.0
  }
  def intervalDuration = {
    val now = System.nanoTime()
    (now.toDouble - intervalStart.toDouble) / 1000000.0
  }

  /**
   * In requests/sec
   */
  def intervalThroughput = intervalCount.toDouble / (intervalDuration / 1000)

  private def mean(values: Seq[Double]) = {
    if (values.size == 0) {
      -1
    } else if (values.size == 1) {
      values(0)
    } else {
      grizzled.math.stats.mean(values: _*)
    }
  }

  def receive = {

    /**
     * Duration in ms
     */
    case EvaluationEnded(duration) =>
      durationsLastInterval += duration
      if (intervalCount % intervalSize == 0) {
        // print
        val meanDurationLastInterval = mean(durationsLastInterval)
        durationsLastIntervals += meanDurationLastInterval
        durationAllIntervals += meanDurationLastInterval
        val throughputLastInterval = intervalThroughput
        throughputLastIntervals += throughputLastInterval
        throughputAllIntervals += throughputLastInterval
        
        val intervalsLabel = f"Mean throughput over last $nbIntervals intervals = " // this is the longest label
        // duration: overall
        println("Mean duration over all intervals = ".padTo(intervalsLabel.size, ' ') + f"${durationAllIntervals.mean}%2.2f ms")
        // duration: last X intervals
        println(f"Mean duration over last $nbIntervals intervals = ".padTo(intervalsLabel.size, ' ') + f"${durationsLastIntervals.mean}%2.2f ms")
        // duration: last interval
        println("Mean duration over last interval = ".padTo(intervalsLabel.size, ' ') + f"$meanDurationLastInterval%2.2f ms")
        // throughput: overall
        println("Mean throughput over all intervals = ".padTo(intervalsLabel.size, ' ') + f"${throughputAllIntervals.mean}%2.2f requests/sec")
        // throughput: last X intervals
        println(intervalsLabel + f"${throughputLastIntervals.mean}%2.2f requests/sec")
        // throughput: last interval
        println("Throughput of last interval = ".padTo(intervalsLabel.size, ' ') + f"$throughputLastInterval%2.2f requests/sec")
        println("".padTo(50, '='))
        
        // flush    
        durationsLastInterval = ListBuffer[Double]()
        intervalStart = System.nanoTime()
      }
  }
}