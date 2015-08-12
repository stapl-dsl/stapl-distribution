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

import scala.util.Random
import stapl.distribution.components.ClientCoordinatorProtocol.AuthorizationRequest

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
  
  def randomRequest() = AuthorizationRequest(randomSubject.id, "view", randomResource.id)

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