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
import stapl.distribution.db.entities.EntityManager

object ConcurrencyEntityManager {
  def apply() = new ConcurrencyEntityManager()
}
class ConcurrencyEntityManager extends EntityManager with Logging {
  
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

  def createSubject(id: String) = {
    val result = new ConcurrencySubject(id, "a-value", "a-value", "a-value", "a-value", "a-value", "a-value", "a-value", "a-value", "a-value", "a-value", List())
    storeEntity(result)
    result
  }

  def createResource(id: String, owner: String) = {
    val result = new ConcurrencyResource(id, owner, 0)
    storeEntity(result)
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
