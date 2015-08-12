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
import collection.mutable.{ HashMap, MultiMap, Set }
import stapl.distribution.util.Counter
import stapl.core.SUBJECT
import stapl.core.RESOURCE

object HashTest extends App {
  
  val nbWorkers = 5
  
  val em = new EhealthEntityManager()
  
  var counter = new Counter("Hashes - subjects", 10000)
  
  em.subjects.foreach { case (id,x) => 
    val key = SUBJECT + ":" + x.id
    val hash = Math.abs(key.hashCode())
    val m = hash % nbWorkers
    counter.count(m)
    println(key)
    println(s"hashcode = $hash")
    println(s"hash % 2 = $m")
    println
  }
  
  counter.print
  
  counter = new Counter("Hashes - resources", 10000)
  
  em.resources.foreach { case (id,x) => 
    val key = RESOURCE + ":" + x.id
    val hash = Math.abs(key.hashCode())
    val m = hash % nbWorkers
    counter.count(m)
    println(key)
    println(s"hashcode = $hash")
    println(s"hash % 2 = $m")
    println
  }
  
  counter.print

}