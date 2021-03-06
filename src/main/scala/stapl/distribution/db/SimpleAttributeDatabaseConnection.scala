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

import java.sql.SQLException
import grizzled.slf4j.Logging
import java.sql.Connection
import com.mchange.v2.c3p0.ComboPooledDataSource
import java.sql.PreparedStatement
import java.sql.ResultSet
import stapl.core.AttributeContainerType
import org.joda.time.LocalDateTime
import stapl.core.Attribute
import stapl.core.String
import stapl.core.Number
import stapl.core.Bool

/**
 *
 * Constructor: sets up an open writable AttributeDatabaseConnection.
 */
class SimpleAttributeDatabaseConnection(initialConnection: Connection, readonly: Boolean = true) extends AttributeDatabaseConnection with Logging {

  initialConnection.setReadOnly(readonly)
  initialConnection.setAutoCommit(true)

  protected var conn: Connection = initialConnection
  private var getStringAttributeStmt: PreparedStatement = initialConnection.prepareStatement("SELECT * FROM attributes WHERE entity_id=? && attribute_container_type=? && attribute_key=?;")
  private var storeAttributeStmt: PreparedStatement = initialConnection.prepareStatement("INSERT INTO attributes VALUES (default, ?, ?, ?, ?);")
  private var updateAttributeStmt: PreparedStatement = initialConnection.prepareStatement("UPDATE attributes SET attribute_value=? WHERE entity_id=? && attribute_container_type=? && attribute_key=?;")

  /**
   * Commits all operations.
   */
  override def commit(): Unit = {
    // don't do anything because a SimpleAttributeDatabaseConnection is always in autocommit.
  }

  /**
   * Closes the connection to the database.
   */
  override def close(): Unit = {
    try {
      getStringAttributeStmt.close()
      storeAttributeStmt.close()
      conn.close()
    } catch {
      case e: SQLException => error("Cannot close connection.", e)
    }
  }

  def cleanStart(): Unit = {
    dropData()
    createTables()
  }

  /**
   * Opens a connection, creates the tables, commits and closes the connection.
   */
  def createTables(): Unit = {
    try {
      val createTablesPS = conn.prepareStatement("CREATE TABLE `attributes` (\n" +
        "  `id` int(11) NOT NULL AUTO_INCREMENT,\n" +
        "  `entity_id` varchar(70) NOT NULL,\n" +
        "  `attribute_container_type` varchar(45) NOT NULL,\n" +
        "  `attribute_key` varchar(45) NOT NULL,\n" +
        "  `attribute_value` varchar(100) NOT NULL,\n" +
        "  PRIMARY KEY (`id`),\n" +
        "  KEY `index` (`entity_id`,`attribute_key`)\n" +
        ");")
      createTablesPS.execute()
      info("Successfully created tables.")
    } catch {
      case e: SQLException => {
        error("Cannot create tables.", e)
        throw new RuntimeException(e)
      }
    }
  }

  /**
   * Opens a connection, drops the data, commits and closes the connection.
   */
  def dropData(): Unit = {
    try {
      val dropDataPS = conn.prepareStatement("DROP TABLE attributes;")
      dropDataPS.execute()
      logger.info("Successfully dropped tables.")
    } catch {
      case e: SQLException => warn(s"Cannot drop tables: ${e.getMessage()}") // no exception needed here
    }
  }

  /**
   * Fetches a string attribute from the database using the connection of this database.
   * Does NOT commit or close.
   */
  def getStringAttribute(entityId: String, cType: AttributeContainerType, name: String): List[String] = {
    var queryResult: ResultSet = null
    try {
      getStringAttributeStmt.setString(1, entityId)
      getStringAttributeStmt.setString(2, cType.toString())
      getStringAttributeStmt.setString(3, name)
      queryResult = getStringAttributeStmt.executeQuery()
      // process the result
      var r = List[String]()
      while (queryResult.next()) {
        r ::= queryResult.getString("attribute_value")
      }
      r
    } catch {
      case e: SQLException => {
        error("Could not fetch string attribute", e)
        throw new RuntimeException("The connection was not open, cannot fetch attribute.")
      }
    } finally {
      if (queryResult != null) {
        queryResult.close()
      }
    }
  }

  /**
   * Stores a string attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def storeAttribute(entityId: String, cType: AttributeContainerType, name: String, value: String): Unit = {
    try {
      storeAttributeStmt.setString(1, entityId)
      storeAttributeStmt.setString(2, cType.toString())
      storeAttributeStmt.setString(3, name)
      storeAttributeStmt.setString(4, value)
      storeAttributeStmt.executeUpdate()
    } catch {
      case e: SQLException => {
        error("Cannot execute query.", e)
        throw new RuntimeException(e)
      }
    }
  }

  /**
   * Updates a string attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def updateAttribute(entityId: String, cType: AttributeContainerType, name: String, value: String): Unit = {
    try {
      updateAttributeStmt.setString(1, value)
      updateAttributeStmt.setString(2, entityId)
      updateAttributeStmt.setString(3, cType.toString())
      updateAttributeStmt.setString(4, name)
      updateAttributeStmt.executeUpdate()
    } catch {
      case e: SQLException => {
        error("Cannot execute query.", e)
        throw new RuntimeException(e)
      }
    }
  }

}