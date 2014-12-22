package stapl.distribution.util

import scala.collection.mutable.Queue

/**
 * Helper class for maintaining a fixed number of interval values.
 * 
 * maxSize = 0 for infinite
 */
class Intervals(val maxSize: Int = 0) {

  private val intervals = Queue[Double]()

  def count() = intervals.size

  def mean() = {
    if (count == 0) {
      -1
    } else if (count == 1) {
      intervals(0)
    } else {
      grizzled.math.stats.mean(intervals: _*)
    }
  }

  def +=(interval: Double) {
    intervals.enqueue(interval)
    if (maxSize != 0 && count > maxSize) {
      intervals.dequeue
    }
  }
}

/**
 * Class used for keeping and printing throughput statistics.
 */
class ThroughputStatistics(intervalSize: Int = 1000, enabled: Boolean = true) {
  val totalStart = System.nanoTime()
  var intervalStart = System.nanoTime()

  var totalCounter = 0L
  var intervalCounter = 0L
  var lastIntervals = new Intervals(10)

  def tick() = {
    if (enabled) {
      totalCounter += 1
      intervalCounter += 1L
      printThroughput
    }
  }

  def totalCount = totalCounter
  def intervalCount = intervalCounter

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
  def totalThroughput = totalCount.toDouble / (totalDuration / 1000)
  def intervalThroughput = intervalCount.toDouble / (intervalDuration / 1000)

  def resetInterval = {
    intervalStart = System.nanoTime()
    intervalCounter = 0
  }

  def printThroughput {
    if (intervalCount % 1000 == 0 && intervalCount > 1000 && intervalDuration > 1000) {
      val i = intervalThroughput
      lastIntervals += i
      // print
      val intervalsLabel = f"Mean throughput over last ${lastIntervals.count} intervals = "
      val total = "Total throughput = ".padTo(intervalsLabel.size, ' ') + f"$totalThroughput%2.2f requests/sec"
      val intervals = intervalsLabel + f"${lastIntervals.mean}%2.2f requests/sec"
      val last = "Last interval throughput = ".padTo(intervalsLabel.size, ' ') + f"$i%2.2f requests/sec"
      println(total)
      println(intervals)
      println(last)
      println("=========".padTo(last.size, '='))
      resetInterval
    }
  }
}