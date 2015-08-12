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