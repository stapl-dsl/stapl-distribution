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

import akka.actor.Actor

/**
 * Duration in ms
 */
case class EvaluationEnded(duration: Double = -1)

case class PrintStats

case class ShutdownAndYouShouldHaveReceived(nb: Int)

/**
 * An actor that wraps a Timer object.
 */
class LatencyStatisticsActor(name: String, printAfterNbRequests: Int = -1) extends Actor {

  val stats = new Timer()

  def receive = {

    /**
     * Duration in ms
     */
    case EvaluationEnded(duration) => {
      stats += duration
      if (stats.count == printAfterNbRequests) {
        println("#! Histogram")
        stats.printHistogram(0.5)
        println("#! Results")
        println(stats.toJSON())
        context.system.shutdown
      }
    }

    case ShutdownAndYouShouldHaveReceived(nb) => {
      println("#! Results")
      println(stats.toJSON(nb))
      context.system.shutdown
    }
  }
}

/**
 * An actor that wraps a ThroughputAndLatencyStatistics object.
 */
class ThroughputAndLatencyStatisticsActor(name: String, intervalSize: Int = 1000, nbIntervals: Int = 10, printIndividualMeasurements: Boolean = false) extends Actor {

  //val stats = new ThroughputStatistics(name, intervalSize, nbIntervals)
  val stats = new ThroughputAndLatencyStatistics(name, intervalSize, nbIntervals, printIndividualMeasurements)

  def receive = {

    /**
     * Duration in ms
     */
    //case EvaluationEnded(duration) => stats.tick()
    case EvaluationEnded(duration) => stats.tick(duration)

    /**
     *
     */
    case PrintStats => stats.printMeasurements
  }
}