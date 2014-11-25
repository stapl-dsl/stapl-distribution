package stapl.distribution.db.entities

import stapl.distribution.db.AttributeDatabaseConnection

abstract class Entity(var id: String) {  
  def persist(db: AttributeDatabaseConnection) // the id does not have to be stored
}