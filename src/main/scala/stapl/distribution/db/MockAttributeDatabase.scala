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
package stapl.distribution.db

import stapl.core.pdp.EvaluationCtx
import stapl.core._
import stapl.core.Attribute
import stapl.distribution.db.entities.ehealth.EhealthEntityManager
import stapl.core.pdp.AttributeFinder
import stapl.core.pdp.PDP
import stapl.examples.policies.EhealthPolicy
import scala.collection.mutable.ListBuffer
import stapl.core.pdp.ObligationService
import scala.collection.mutable.MultiMap

class MockAttributeDatabaseConnection extends AttributeDatabaseConnection {

  def cleanStart(): Unit = ???

  def commit(): Unit = ???

  def createTables(): Unit = ???

  def dropData(): Unit = ???

  def getStringAttribute(entityId: String, cType: stapl.core.AttributeContainerType, name: String): List[String] = ???

  def storeAttribute(entityId: String, cType: stapl.core.AttributeContainerType, name: String, value: String): Unit = ???

  def close() = {} // don't throw an error here, this method is called when a Worker is destroyed

  val receivedAttributeUpdates = ListBuffer[(String, Attribute, Any)]()

  override def storeAnyAttribute(entityId: String, attribute: Attribute, value: Any) = {
    receivedAttributeUpdates += new Tuple3(entityId, attribute, value)
  }

  def updateAttribute(entityId: String, cType: AttributeContainerType, name: String, value: String): Unit = ???
}

class MockAttributeDatabaseConnectionPool extends AttributeDatabaseConnectionPool {

  override def getConnection = new MockAttributeDatabaseConnection
}