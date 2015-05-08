package stapl.distribution.db.entities

import stapl.distribution.db.AttributeDatabaseConnection
import stapl.distribution.components.ClientCoordinatorProtocol.AuthorizationRequest

/**
 * Nothing more than concrete subtypes for now, but made this a
 * separate subtype so we could add random attributes or something
 * later on.
 */
class ArtificialSubject(id: String) extends SubjectEntity(id) {

  override def persist(db: AttributeDatabaseConnection) {
    throw new UnsupportedOperationException
  }
}
class ArtificialResource(id: String) extends ResourceEntity(id) {

  override def persist(db: AttributeDatabaseConnection) {
    throw new UnsupportedOperationException
  }
}

object ArtificialEntityManager {
  
  def apply(nbSubjects: Int = 1000, nbResources: Int = 1000) = new ArtificialEntityManager(nbSubjects, nbResources)
}
class ArtificialEntityManager(nbSubjects: Int = 1000, nbResources: Int = 1000) extends EntityManager {

  // create the entities
  for (i <- 1 to nbSubjects) {
    storeEntity(new ArtificialSubject(s"subject$i"))
  }
  for (i <- 1 to nbResources) {
    storeEntity(new ArtificialResource(s"resource$i"))
  }
}