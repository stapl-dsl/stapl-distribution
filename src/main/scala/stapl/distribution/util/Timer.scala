package stapl.distribution.util

import Numeric._

/**
 * Class used for representing a timer that times code evaluation,
 * keeps all values of the test and provides some statistics about 
 * these timings.
 * 
 * The timer can register timings in two ways:
 * 1. start() and stop()
 * 2. time()
 * 
 * These cannot be used in parallel!
 */
class Timer {
  
  // timings in milliseconds
  var timings = List[Double]()
  
  var t0 = 0L
  var t1 = 0L
  
  def start() = t0 = System.nanoTime()
  def stop() = t1 = System.nanoTime()
  def duration() = (t1.toDouble - t0.toDouble) / 1000000.0

  def time[R](block: => R): R = {
    start
    val result = block // call-by-name
    stop
    timings ::= duration 
    result
  }
  
  def count = timings.size
  
  def mean = grizzled.math.stats.mean(timings: _*)
  
  def stdDev = grizzled.math.stats.sampleStdDev(timings: _*)
  
  def total = timings.sum
  
  /**
   * The size of the confidence interval wrt to the avg.
   * 
   * @param d How do you call this?
   */
  def confInt(d: Double = 1.95996398454): Double = {
    // Inverse normal distributions (results of norm.ppf((1 + x)/2.0) in python):
	// x = 0.9 -> 1.64485362695
	// x = 0.91 -> 1.69539771027
	// x = 0.92 -> 1.75068607125
	// x = 0.93 -> 1.81191067295
	// x = 0.94 -> 1.88079360815
	// x = 0.95 -> 1.95996398454
	// x = 0.96 -> 2.05374891063
	// x = 0.97 -> 2.17009037758
	// x = 0.98 -> 2.32634787404
	// x = 0.99 -> 2.57582930355
	// x = 0.999 -> 3.29052673149
    val confIntervalLow  = mean - (d * stdDev / math.sqrt(count))
    val confIntervalHigh = mean + (d * stdDev / math.sqrt(count))
    val confIntervalSizeAbs = confIntervalHigh - confIntervalLow
    confIntervalSizeAbs / mean
  }
  
  def reset = {
    timings = List.empty
  }
  
}