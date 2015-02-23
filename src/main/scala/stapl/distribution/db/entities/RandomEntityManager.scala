package stapl.distribution.db.entities

import stapl.distribution.db.AttributeDatabaseConnection

/**
 * Nothing more than concrete subtypes for now, but made this a
 * separate subtype so we could add random attributes or something
 * later on.
 */
class RandomSubject(id: String) extends SubjectEntity(id) {

  override def persist(db: AttributeDatabaseConnection) {
    throw new UnsupportedOperationException
  }
}
class RandomResource(id: String) extends ResourceEntity(id) {

  override def persist(db: AttributeDatabaseConnection) {
    throw new UnsupportedOperationException
  }
}

class RandomEntityManager(nbSubjects: Int = 1000, nbResources: Int = 1000) extends EntityManager {

  // create the entities
  for (i <- 1 to nbSubjects) {
    storeEntity(new RandomSubject(s"subject$i"))
  }
  for (i <- 1 to nbResources) {
    storeEntity(new RandomResource(s"resource$i"))
  }
}