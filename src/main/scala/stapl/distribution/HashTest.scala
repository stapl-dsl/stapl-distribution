package stapl.distribution

import stapl.distribution.db.entities.ehealth.EntityManager
import collection.mutable.{ HashMap, MultiMap, Set }
import stapl.distribution.util.Counter
import stapl.core.SUBJECT

object HashTest extends App {
  
  val em = EntityManager()
  
  val counter = new Counter("Hashes", 10000)
  
  em.subjects.foreach { case (id,x) => 
    val key = SUBJECT + ":" + x.id
    val hash = Math.abs(key.hashCode())
    val m = hash % 2
    counter.count(m)
    println(key)
    println(s"hashcode = $hash")
    println(s"hash % 2 = $m")
    println
  }
  
  counter.print

}