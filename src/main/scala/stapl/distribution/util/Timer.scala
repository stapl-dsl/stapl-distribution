/**
 *    Copyright 2015 KU Leuven Research and Developement - iMinds - Distrinet
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    Administrative Contact: dnet-project-office@cs.kuleuven.be
 *    Technical Contact: maarten.decat@cs.kuleuven.be
 *    Author: maarten.decat@cs.kuleuven.be
 */
package stapl.distribution.util

import Numeric._
import spray.json._
import scala.collection.mutable.ListBuffer

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
class Timer(label: String = "unnamed-timer") {

  // timings in milliseconds
  val timings = ListBuffer[Double]()
  var last = -1.0
  var whenFirstReceived = -1L
  var whenLastReceived = -1L

  var t0 = 0L
  var t1 = 0L

  def start() = t0 = System.nanoTime()
  def stop() = {
    t1 = System.nanoTime()
    this += (t1.toDouble - t0.toDouble) / 1000000.0
  }
  
  def +=(duration: Double) = {
    last = duration
    timings += last
    // log the time of the first and the last received message 
    val now = System.nanoTime()
    if (whenFirstReceived == -1) {
      whenFirstReceived = now
    }
    whenLastReceived = now
  }

  def time[R](block: => R): R = {
    start
    val result = block // call-by-name
    stop
    result
  }

  def count = timings.size

  def mean = {
    if (count == 0) {
      -1
    } else if (count == 1) {
      timings(0)
    } else {
      grizzled.math.stats.mean(timings: _*)
    }
  }

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
    val confIntervalLow = mean - (d * stdDev / math.sqrt(count))
    val confIntervalHigh = mean + (d * stdDev / math.sqrt(count))
    val confIntervalSizeAbs = confIntervalHigh - confIntervalLow
    confIntervalSizeAbs / mean
  }
  
  /**
   * Returns the number of seconds we have been receiving measurements
   * based on the time when the first and the last
   * measurements were received and the number of measurements received.
   */
  def secondsReceiving(): Double = (whenLastReceived - whenFirstReceived).toDouble / 1000.0 / 1000.0 / 1000.0

  /**
   * Returns the average throughput of received measurements in
   * requests / second based on the time when the first and the last
   * measurements were received and the number of measurements received.
   */
  def throughput(): Double = count.toDouble / secondsReceiving

  def reset = {
    timings.clear
    last = -1.0
    whenFirstReceived = -1L
    whenLastReceived = -1L
  }

  override def toString(): String = {
    return f"$label: nbruns = $count, mean = $mean%2.2f ms, confInt = ${confInt() * 100}%2.2f%%"
  }

  def toJSON(nbSent: Int = -1) = JsObject(
    "label" -> JsString(label),
    "nbSent" -> JsNumber(nbSent),
    "nbReceived" -> JsNumber(count),
    "mean" -> JsNumber(mean),
    "confInt" -> JsNumber(confInt()),
    "secondsReceiving" -> JsNumber(secondsReceiving),
    "throughput" -> JsNumber(throughput) /*,
    "values" -> JsArray(timings.map(JsNumber(_)).toVector)*/ ).compactPrint

  def printAllMeasurements() = {
    timings.foreach(println(_))
  }

  def printHistogram(binSize: Double): Unit = {
    val max = timings.foldLeft(0.0)((a, b) => math.max(a, b))
    if(max <= 0) { // this can happen in case we don't care for latencies but still use this timer 
      return
    }
    //val min = timings.foldLeft(Double.MaxValue)((a,b) => math.min(a,b))
    val min = 0
    val nbBins = math.ceil(max / binSize).toInt

    // put the values in bins
    val bins = ListBuffer[Int]()
    (1 to nbBins).foreach(x => bins.append(0))
    timings.foreach(x => {
      val index = math.floor((x - min) / binSize).toInt
      bins(index) = bins(index) + 1
    })

    // print the values
    val nbCharacters = 50
    val maxBin = bins.foldLeft(0)((a, b) => math.max(a, b))
    val characterSize = (maxBin.toDouble / nbCharacters.toDouble).ceil
    val intervalSize = f"[${(nbBins - 1) * binSize + min},${nbBins * binSize + min})".size
    val labelSize = s"${maxBin}".size
    for (i <- 0 until nbBins) {
      val value = bins(i)
      val lowerBound = i * binSize + min
      val upperBound = (i + 1) * binSize + min
      val interval = f"[$lowerBound,$upperBound)".padTo(intervalSize, ' ')
      val label = s"$value".padTo(labelSize, ' ')
      val bar = "".padTo(math.round((value - min) / characterSize).toInt, '=')
      println(s"$interval | $label | $bar")
    }
  }

}