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

import stapl.distribution.db.entities.ehealth.EhealthEntityManager
import stapl.distribution.components.SimpleDistributedCoordinatorLocater
import akka.actor.ActorSystem
import akka.actor.Props
import scala.collection.mutable.Map

object DistributedCoordinatorDistribution extends App {

  val nbTests = 100000

  for (i <- 2 to 10) {
    // set up the ActorRefs (not really actors or coordinators)
    val system = ActorSystem("test-system")
    val coordinators = (1 to i).map(x => system.actorOf(Props(classOf[NodeCActor]), s"actor$x")).toList
    val locater = new SimpleDistributedCoordinatorLocater()
    locater.setCoordinators(coordinators)

    val em = EhealthEntityManager(true)
    
    val subjectResults = Map((0 until i).map(x => coordinators(x) -> 0): _*)
    val resourceResults = Map((0 until i).map(x => coordinators(x) -> 0): _*)
    
    for (test <- 1 to nbTests) {
      val request = em.randomRequest
      val subjectCoordinator = locater.getCoordinatorForSubject(request.subjectId)
      val resourceCoordinator = locater.getCoordinatorForResource(request.resourceId)
      subjectResults(subjectCoordinator) = subjectResults(subjectCoordinator) + 1
      resourceResults(resourceCoordinator) = resourceResults(resourceCoordinator) + 1
    }
    
    // print the results
    println(s"For $i coordinators")
    println("========================")
    println()
    println("Subjects:")
    for(coordinator <- coordinators) {
      println(f"${subjectResults(coordinator)/nbTests.toDouble*100}%.2f%%")
    }
    println()
    println("Resources:")
    for(coordinator <- coordinators) {
      println(f"${resourceResults(coordinator)/nbTests.toDouble*100}%.2f%%")
    }
    println()
    
    
    system.shutdown
    system.awaitTermination
  }
}