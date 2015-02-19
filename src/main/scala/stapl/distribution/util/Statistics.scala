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
class ThroughputStatistics(name: String = "Anonymous timer", intervalSize: Int = 1000, nbIntervals: Int = 10, enabled: Boolean = true) {
  var totalStart = 0L
  var intervalStart = 0L
  var started = false

  var totalCounter = 0L
  var intervalCounter = 0L
  var lastIntervals = new Intervals(nbIntervals)

  def tick() = {
    if (enabled) {
      if (!started) {
        totalStart = System.nanoTime()
        intervalStart = System.nanoTime()
        started = true
      }
      totalCounter += 1L
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
      println(s"=== $name ".padTo(50, '='))
      println("Total count = ".padTo(intervalsLabel.size, ' ') + f"$totalCounter requests")
      println(total)
      println(intervals)
      println(last)
      println("=========".padTo(50, '='))
      resetInterval
    }
  }
}

/**
 *
 */
class ThroughputAndLatencyStatistics(name: String = "Anonymous timer", intervalSize: Int = 1000, nbIntervals: Int = 10,
  enabled: Boolean = true) {

  import scala.collection.mutable.ListBuffer

  var durationsLastInterval = ListBuffer[Double]()
  val durationsLastIntervals = new Intervals(nbIntervals)
  val throughputLastIntervals = new Intervals(nbIntervals)
  val durationAllIntervals = new Intervals()
  val throughputAllIntervals = new Intervals()

  def totalCount = durationAllIntervals.count.toLong * intervalSize + durationsLastInterval.size
  def intervalCount = durationsLastInterval.size

  /**
   * Helper function to get the current timestamp.
   */
  private def now() = System.nanoTime()

  var totalStart = 0L
  var intervalStart = 0L
  var started = false

  /**
   * Helper function to convert long timestamps to milliseconds.
   */
  private def toMs(from: Long, to: Long) = (to.toDouble - from.toDouble) / 1000000.0

  /**
   * The duration in ms
   */
  def totalDuration = toMs(totalStart, now)
  def intervalDuration = toMs(intervalStart, now)

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

  private val starts = scala.collection.mutable.Map[Any, Long]()

  /**
   * Indicates that an iteration with the given id starts now.
   *
   * Overwrites the administration of another started iteration with the
   * same id if present.
   */
  def start(id: Any) = {
    starts(id) = now
  }

  /**
   * Indicates that an iteration with the given id ends now.
   * Inserts the duration of this iteration into the administration
   * and removes this id from the administration.
   */
  def stop(id: Any) = {
    val duration = toMs(starts(id), now)
    tick(duration)
    starts.remove(id)
  }

  /**
   * Indicates that another iteration (of whatever) has ended with
   * no particular duration.
   */
  def tick(): Unit = tick(-1)

  /**
   * Indicates that another iteration (of whatever) has ended with
   * given duration.
   *
   * @param duration In ms.
   */
  def tick(duration: Double): Unit = {
    if (enabled) {
      if (!started) {
        totalStart = now
        intervalStart = now
        started = true
      }
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
        println(s"=== $name (intervals of $intervalSize requests)".padTo(70, '='))
        println(s"Total count = ".padTo(intervalsLabel.size, ' ') + f"$totalCount requests")
        // duration: overall
        println(s"Mean duration over ${durationAllIntervals.count} intervals = ".padTo(intervalsLabel.size, ' ') + f"${durationAllIntervals.mean}%2.2f ms")
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
        println("".padTo(70, '='))

        // flush    
        durationsLastInterval = ListBuffer[Double]()
        intervalStart = System.nanoTime()
      }

    }
  }
}

/**
 *
 */
class LatencyStatistics(name: String = "Anonymous timer", intervalSize: Int = 1000, nbIntervals: Int = 10,
  enabled: Boolean = true) {

  import scala.collection.mutable.ListBuffer

  var durationsLastInterval = ListBuffer[Double]()
  val durationsLastIntervals = new Intervals(nbIntervals)
  val durationAllIntervals = new Intervals()

  def totalCount = durationsLastInterval.size * intervalSize + durationsLastInterval.size
  def intervalCount = durationsLastInterval.size

  /**
   * Helper function to get the current timestamp.
   */
  private def now() = System.nanoTime()

  val totalStart = now
  var intervalStart = now

  /**
   * Helper function to convert long timestamps to milliseconds.
   */
  private def toMs(from: Long, to: Long) = (to.toDouble - from.toDouble) / 1000000.0

  /**
   * The duration in ms
   */
  def totalDuration = toMs(totalStart, now)
  def intervalDuration = toMs(intervalStart, now)

  private def mean(values: Seq[Double]) = {
    if (values.size == 0) {
      -1
    } else if (values.size == 1) {
      values(0)
    } else {
      grizzled.math.stats.mean(values: _*)
    }
  }

  private val starts = scala.collection.mutable.Map[Any, Long]()

  /**
   * Indicates that an iteration with the given id starts now.
   *
   * Overwrites the administration of another started iteration with the
   * same id if present.
   */
  def start(id: Any) = {
    starts(id) = now
  }

  /**
   * Indicates that an iteration with the given id ends now.
   * Inserts the duration of this iteration into the administration
   * and removes this id from the administration.
   */
  def stop(id: Any) = {
    val duration = toMs(starts(id), now)
    tick(duration)
    starts.remove(id)
  }

  /**
   *
   */
  def time[R](block: => R): R = {
    val start = now
    val result = block // call-by-name
    tick(toMs(start, now))
    result
  }

  /**
   * Indicates that another iteration (of whatever) has ended with
   * no particular duration.
   */
  def tick(): Unit = tick(-1)

  /**
   * Indicates that another iteration (of whatever) has ended with
   * given duration.
   *
   * @param duration In ms.
   */
  def tick(duration: Double): Unit = {
    if (enabled) {
      durationsLastInterval += duration
      if (intervalCount % intervalSize == 0) {
        // print
        val meanDurationLastInterval = mean(durationsLastInterval)
        durationsLastIntervals += meanDurationLastInterval
        durationAllIntervals += meanDurationLastInterval

        println(s"=== $name ".padTo(70, '='))
        val intervalsLabel = f"Mean throughput over last $nbIntervals intervals = " // this is the longest label
        // duration: overall
        println(s"Mean duration over ${durationAllIntervals.count} intervals = ".padTo(intervalsLabel.size, ' ') + f"${durationAllIntervals.mean}%2.2f ms")
        // duration: last X intervals
        println(f"Mean duration over last $nbIntervals intervals = ".padTo(intervalsLabel.size, ' ') + f"${durationsLastIntervals.mean}%2.2f ms")
        // duration: last interval
        println("Mean duration over last interval = ".padTo(intervalsLabel.size, ' ') + f"$meanDurationLastInterval%2.2f ms")
        println("".padTo(70, '='))

        // flush    
        durationsLastInterval = ListBuffer[Double]()
        intervalStart = System.nanoTime()
      }
    }
  }
}

/**
 *
 */
class Counter(name: String, intervalSize: Int = 1000) {

  val counts = scala.collection.mutable.Map[Any, Int]()

  var leftInThisInterval = intervalSize

  def count(key: Any) {
    if (counts.contains(key)) {
      counts(key) = counts(key) + 1
    } else {
      counts(key) = 1
    }
    leftInThisInterval -= 1
    if (leftInThisInterval == 0) {
      print
    }
  }

  def reset() {
    counts.clear
  }

  def print() {
    val total = counts.values.sum.toDouble
    // print
    println(s"=== Counter: $name ".padTo(70, '='))
    counts.foreach {
      case (key, count) =>
        println(f"$key: $count (${count.toDouble / total * 100}%2.2f%%)")
    }
    println("".padTo(70, '='))

    // flush    
    reset
    leftInThisInterval = intervalSize
  }

}
