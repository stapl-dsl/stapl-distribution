package stapl.distribution.db.entities.concurrency

import org.joda.time.LocalDateTime
import stapl.distribution.db.AttributeDatabaseConnection
import stapl.distribution.db.entities.Entity
import stapl.distribution.policies.ConcurrencyPolicies
import stapl.distribution.db.entities.SubjectEntity
import stapl.distribution.db.entities.ResourceEntity

class ConcurrencySubject(id: String, val attribute1: String, val attribute2: String, val attribute3: String,
  val attribute4: String, val attribute5: String, val attribute6: String, val attribute7: String, 
  val attribute8: String, val attribute9: String, val attribute10: String,
  val history: List[String]) extends SubjectEntity(id) {

  override def persist(db: AttributeDatabaseConnection) = {
    db.storeAttribute(id, ConcurrencyPolicies.subject.attribute1, attribute1)
    db.storeAttribute(id, ConcurrencyPolicies.subject.attribute2, attribute2)
    db.storeAttribute(id, ConcurrencyPolicies.subject.attribute3, attribute3)
    db.storeAttribute(id, ConcurrencyPolicies.subject.attribute4, attribute4)
    db.storeAttribute(id, ConcurrencyPolicies.subject.attribute5, attribute5)
    db.storeAttribute(id, ConcurrencyPolicies.subject.attribute6, attribute6)
    db.storeAttribute(id, ConcurrencyPolicies.subject.attribute7, attribute7)
    db.storeAttribute(id, ConcurrencyPolicies.subject.attribute8, attribute8)
    db.storeAttribute(id, ConcurrencyPolicies.subject.attribute9, attribute9)
    db.storeAttribute(id, ConcurrencyPolicies.subject.attribute10, attribute10)
    db.storeAttribute(id, ConcurrencyPolicies.subject.history, history)
  }
}

class ConcurrencyResource(id: String, val owner: String, val nbAccesses: Int) extends ResourceEntity(id) {
    
  override def persist(db: AttributeDatabaseConnection) {
    db.storeAttribute(id, ConcurrencyPolicies.resource.owner, owner)
    db.storeAttribute(id, ConcurrencyPolicies.resource.nbAccesses, nbAccesses)
  }   
}