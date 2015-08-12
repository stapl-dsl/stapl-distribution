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

class InMemoryAttributeDatabaseConnection extends AttributeDatabaseConnection {

  import stapl.core.AttributeContainerType

  /**
   * Threadsafe!
   */
  private val attributes = new scala.collection.concurrent.TrieMap[(String, stapl.core.AttributeContainerType, String), String]

  def cleanStart(): Unit = dropData

  def commit(): Unit = {}

  def createTables(): Unit = {}

  def dropData(): Unit = attributes.clear

  def getStringAttribute(entityId: String, cType: AttributeContainerType, name: String): List[String] =
    attributes.get((entityId, cType, name)) match {
      case None => List()
      case Some(x) => List(x)
    }

  def storeAttribute(entityId: String, cType: AttributeContainerType, name: String, value: String): Unit =
    attributes((entityId, cType, name)) = value

  def close() = {}

  /**
   * Updates a string attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def updateAttribute(entityId: String, cType: AttributeContainerType, name: String, value: String): Unit =
    storeAttribute(entityId: String, cType: AttributeContainerType, name: String, value: String)
}

class InMemoryAttributeDatabaseConnectionPool extends AttributeDatabaseConnectionPool {

  val connection = new InMemoryAttributeDatabaseConnection

  def getConnection = connection
}