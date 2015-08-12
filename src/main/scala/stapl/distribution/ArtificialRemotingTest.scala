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
package stapl.distribution

import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.pattern.ask
import akka.util.Timeout
import stapl.distribution.db.entities.EntityManager
import stapl.distribution.db.entities.ehealth.EhealthEntityManager
import stapl.distribution.components.ClientCoordinatorProtocol
import stapl.core.Deny

object NodeAApp {

  def main(args: Array[String]) {
    val defaultConf = ConfigFactory.load()
    val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = 127.0.0.1
        akka.remote.netty.tcp.port = 2552
      """).withFallback(defaultConf)
    val system = ActorSystem("node-a", customConf)

    val selection = system.actorSelection("akka.tcp://node-b@127.0.0.1:2553/user/actor")
    implicit val dispatcher = system.dispatcher
    implicit val timeout = Timeout(5.second)
    val nodeB = Await.result(selection.resolveOne(3.seconds), 5.seconds)

    // warmup
    println("Warmup")
    for (i <- 1 to 1000) {
      Await.result(nodeB ? System.nanoTime(), 5.seconds) match {
        case msg => // nothing to do
      }
    }    
    
    // the test
    println("Tests")
    val totals, steps1, steps2, steps3 = ListBuffer[Double]()
    for (i <- 1 to 10000) {
      if(i % 1000 == 0) {
        println(s"$i")
      }
      Await.result(nodeB ? System.nanoTime(), 5.seconds) match {
        case timings: (Long, Long, Long, ClientCoordinatorProtocol.AuthorizationDecision) =>
          val (start, atNodeB, atNodeC, decision) = timings
          val stop = System.nanoTime()
          totals += duration(start, stop)
          steps1 += duration(start, atNodeB)
          steps2 += duration(atNodeB, atNodeC)
          steps3 += duration(atNodeC, stop)
      }
    }
    
    println("Totals")
    printHistogram(totals)
    println("A -> B")
    printHistogram(steps1)
    println("B -> C")
    printHistogram(steps2)
    println("C -> A")
    printHistogram(steps3)

    /**
     * Helper function to print a list of timings as a histogram
     */
    def printHistogram(timings: Seq[Double]) = {
      val binSize = 0.5
      val max = timings.foldLeft(0.0)((a, b) => math.max(a, b))
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
      val characterSize = (maxBin / nbCharacters).ceil
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

  /**
   * Helper function to convert two Long timestamps to a duration in ms.
   */
  def duration(from: Long, to: Long) = (to - from).toDouble / 1000000.0
}

object NodeBApp {

  def main(args: Array[String]) {
    val defaultConf = ConfigFactory.load()
    val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = 127.0.0.1
        akka.remote.netty.tcp.port = 2553
      """).withFallback(defaultConf)
    val system = ActorSystem("node-b", customConf)

    val selection = system.actorSelection("akka.tcp://node-c@127.0.0.1:2554/user/actor")
    implicit val dispatcher = system.dispatcher
    implicit val timeout = Timeout(5.second)
    val nodeC = Await.result(selection.resolveOne(3.seconds), 5.seconds)

    system.actorOf(Props(classOf[NodeBActor], nodeC), "actor")
  }
}
class NodeBActor(nodeC: ActorRef) extends Actor {

  val em = EhealthEntityManager()
  
  def receive = {
    case start: Long =>
      val request = em.randomRequest
      nodeC ! (sender, start, System.nanoTime(), ClientCoordinatorProtocol.AuthorizationRequest(request.subjectId, request.actionId, request.resourceId, request.extraAttributes))
  }
}

object NodeCApp {

  def main(args: Array[String]) {
    val defaultConf = ConfigFactory.load()
    val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = 127.0.0.1
        akka.remote.netty.tcp.port = 2554
      """).withFallback(defaultConf)
    val system = ActorSystem("node-c", customConf)

    system.actorOf(Props(classOf[NodeCActor]), "actor")
  }
}
class NodeCActor extends Actor {
  
  def receive = {
    case msg: (ActorRef, Long, Long, ClientCoordinatorProtocol.AuthorizationRequest) =>
      val (originalSender, start, atNodeB, request) = msg
      originalSender ! (start, atNodeB, System.nanoTime(), ClientCoordinatorProtocol.AuthorizationDecision("evaluationId", Deny))
  }
}








