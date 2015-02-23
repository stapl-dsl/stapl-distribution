package stapl.distribution.db.entities

import scala.util.Random

trait EntityManager {

  val entities = scala.collection.mutable.Map[String, Entity]()
  val subjects = scala.collection.mutable.Map[String, SubjectEntity]()
  val resources = scala.collection.mutable.Map[String, ResourceEntity]()

  def getEntity(id: String) = entities.get(id)
  
  def randomEntity() = {
    val keys = entities.keySet
    val random = keys.toVector(Random.nextInt(keys.size))
    entities(random)
  }
  
  def randomSubject() = {
    val keys = subjects.keySet
    val random = keys.toVector(Random.nextInt(keys.size))
    subjects(random)
  }
  
  def randomResource() = {
    val keys = resources.keySet
    val random = keys.toVector(Random.nextInt(keys.size))
    resources(random)
  }  

  protected def storeEntity(e: Entity) = {
    if (entities.contains(e.id)) {
      error("Duplicate entity id found: " + e.id)
      throw new RuntimeException(s"Duplicate entity id found: ${e.id}")
    }
    entities.put(e.id, e)
    if (e.isInstanceOf[SubjectEntity]) subjects.put(e.id, e.asInstanceOf[SubjectEntity])
    if (e.isInstanceOf[ResourceEntity]) resources.put(e.id, e.asInstanceOf[ResourceEntity])
  }
}