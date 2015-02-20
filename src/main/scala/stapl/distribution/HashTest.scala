package stapl.distribution

import stapl.distribution.db.entities.ehealth.EntityManager
import collection.mutable.{ HashMap, MultiMap, Set }
import stapl.distribution.util.Counter
import stapl.core.SUBJECT
import stapl.core.RESOURCE

object HashTest extends App {
  
  val nbWorkers = 5
  
  val em = EntityManager()
  
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