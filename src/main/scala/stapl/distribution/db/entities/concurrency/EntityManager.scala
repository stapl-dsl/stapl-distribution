package stapl.distribution.db.entities.concurrency

import grizzled.slf4j.Logging
import org.joda.time.LocalDateTime
import stapl.distribution.db.AttributeDatabaseConnection
import stapl.core.ConcreteValue
import stapl.core.Result
import stapl.core.Attribute
import stapl.core.Permit
import stapl.core.Deny
import stapl.core.Decision
import stapl.distribution.db.entities.DateHelper
import stapl.distribution.db.entities.Entity

object EntityManager {
  def apply() = new EntityManager()
}
class EntityManager extends Logging {

  val entities = scala.collection.mutable.Map[String, Entity]()
  val resources = scala.collection.mutable.ListBuffer[Resource]()
  val subjects = scala.collection.mutable.ListBuffer[Subject]()
  val bank1 = "bank1"
  val bank2 = "bank2"
  val owners = List(bank1, bank2)

  implicit def entity2id(e: Entity): String = e.id
  implicit def decision2Result(d: Decision): Result = Result(d)

  val resourceOfBank1 = createResource("resource1", bank1)
  val resourceOfBank2 = createResource("resource2", bank2)
  val subject1 = createSubject("subject1")
  for(i <- 2 to 1000) {
    createSubject(s"subject$i")
  }

  /**
   * ********************************
   * ENTITY STORE
   * ********************************
   */

  def getEntity(id: String) = entities.get(id)

  def storeEntity(e: Entity) = {
    if (entities.contains(e.id)) {
      error("Duplicate entity id found: " + e.id)
      throw new RuntimeException(s"Duplicate entity id found: ${e.id}")
    }
    entities.put(e.id, e)
  }

  def createSubject(id: String) = {
    val result = new Subject(id, "a-value", "a-value", "a-value", "a-value", "a-value", "a-value", "a-value", "a-value", "a-value", "a-value", List())
    storeEntity(result)
    subjects += result
    result
  }

  def createResource(id: String, owner: String) = {
    val result = new Resource(id, owner, 0)
    storeEntity(result)
    resources += result
    result
  }

  /**
   * ********************************
   * PERSISTING TO THE DATABASE
   * ********************************
   */

  def persist(db: AttributeDatabaseConnection) {
    entities.values.foreach(_.persist(db))
  }

  /**
   * ********************************
   * HELPER: RANDOM IDS
   * ********************************
   */

  var counter = 0

  def randomId() = {
    counter += 1
    s"dkDe8dD4D0etp$counter"
  }

}
