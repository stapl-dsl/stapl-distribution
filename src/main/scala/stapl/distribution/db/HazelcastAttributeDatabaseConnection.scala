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
import com.hazelcast.core.MapStore
import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.IMap
import java.util.HashMap
import com.hazelcast.config.MapConfig
import com.hazelcast.config.MapStoreConfig
import com.hazelcast.core.HazelcastInstance

class HazelcastAttributeDatabaseConnectionPool(hazelcast: HazelcastInstance)
  extends AttributeDatabaseConnectionPool {
  
  override def getConnection = new HazelcastAttributeDatabaseConnection(hazelcast.getMap("stapl-attributes"))
}

/**
 *
 */
class AttributeMapStore(host: String, port: Int, database: String, username: String, password: String)
  extends MapStore[(String, AttributeContainerType, String), List[String]] with Logging {

  private val dataSource = new ComboPooledDataSource
  dataSource.setMaxPoolSize(30); // no maximum
  dataSource.setMinPoolSize(1); // no maximum
  dataSource.setDriverClass("com.mysql.jdbc.Driver");
  dataSource.setUser(username);
  dataSource.setPassword(password);
  dataSource.setJdbcUrl(s"jdbc:mysql://$host:$port/$database");
  val conn = dataSource.getConnection
  conn.setAutoCommit(true)

  import scala.collection.JavaConversions._

  def load(key: (String, AttributeContainerType, String)): List[String] = {
    debug(s"Loading values for $key")
    val (entityId, cType, name) = key
    var queryResult: ResultSet = null
    try {
      val getStringAttributeStmt: PreparedStatement = conn.prepareStatement("SELECT * FROM attributes WHERE entity_id=? && attribute_container_type=? && attribute_key=?;")
      getStringAttributeStmt.setString(1, entityId)
      getStringAttributeStmt.setString(2, cType.toString())
      getStringAttributeStmt.setString(3, name)
      queryResult = getStringAttributeStmt.executeQuery()
      // process the result
      var r = List[String]()
      while (queryResult.next()) {
        r ::= queryResult.getString("attribute_value")
      }
      debug(s"Values found for $key: $r")
      r
    } catch {
      case e: SQLException => {
        error("Could not fetch string attribute", e)
        throw e
      }
    } finally {
      if (queryResult != null) {
        queryResult.close()
      }
    }
  }

  /**
   * Very inefficient implementation
   */
  def loadAll(keys: java.util.Collection[(String, AttributeContainerType, String)]): java.util.Map[(String, AttributeContainerType, String), List[String]] = {
    val result: java.util.Map[(String, AttributeContainerType, String), List[String]] = new HashMap()
    import scala.collection.JavaConversions._
    keys.foreach { key =>
      result(key) = load(key)
    }
    result
  }

  def loadAllKeys(): java.util.Set[(String, AttributeContainerType, String)] = {
    null // this means that nothing will be preloaded at initialization time
  }

  def delete(key: (String, AttributeContainerType, String)): Unit = {
    val (entityId, cType, name) = key
    try {
      val removeAttributeStmt: PreparedStatement = conn.prepareStatement("DELETE FROM attributes WHERE entity_id=? && attribute_container_type=? && attribute_key=?;")
      removeAttributeStmt.setString(1, entityId)
      removeAttributeStmt.setString(2, cType.toString())
      removeAttributeStmt.setString(3, name)
      removeAttributeStmt.executeUpdate()
    } catch {
      case e: SQLException => {
        error("Cannot execute query.", e)
        throw new RuntimeException(e)
      }
    }
  }

  /**
   * Very inefficient implementation.
   */
  def deleteAll(keys: java.util.Collection[(String, AttributeContainerType, String)]): Unit = {
    keys.foreach {
      key => delete(key)
    }
  }

  def store(key: (String, AttributeContainerType, String), value: List[String]): Unit = {
    val (entityId, cType, name) = key
    // first delete all old values
    delete(key)
    // then store all new values
    try {
      val storeAttributeStmt: PreparedStatement = conn.prepareStatement("INSERT INTO attributes VALUES (default, ?, ?, ?, ?);")
      value foreach { x =>
        storeAttributeStmt.setString(1, entityId)
        storeAttributeStmt.setString(2, cType.toString())
        storeAttributeStmt.setString(3, name)
        storeAttributeStmt.setString(4, x)
        storeAttributeStmt.addBatch()
      }
      storeAttributeStmt.executeBatch()
    } catch {
      case e: SQLException => {
        error("Cannot execute query.", e)
        throw new RuntimeException(e)
      }
    }
  }

  /**
   * Very inefficient implementation.
   */
  def storeAll(values: java.util.Map[(String, AttributeContainerType, String), List[String]]): Unit = {
    values.foreach { keyvalue =>
      val (key, value) = keyvalue
      store(key, value)
    }
  }

}

/**
 * Constructor: sets up an open writable AttributeDatabaseConnection.
 *
 * @param	coordinatorLocation
 * 			The IP of the coordinator as a string.
 */
class HazelcastAttributeDatabaseConnection(hazelcast: IMap[(String, AttributeContainerType, String), List[String]])
  extends AttributeDatabaseConnection with Logging {
  
  override def close() = {
    // nothing to do (I think)
  }
  
  override def commit() {
    // nothing to do (I think)
  }

  /**
   * Opens a connection, drops the data, commits and closes the connection.
   */
  def dropData(): Unit = {
    hazelcast.destroy // TODO is this the correct method to call here?
  }

  /**
   * Fetches a string attribute from the database using the connection of this database.
   */
  def getStringAttribute(entityId: String, cType: AttributeContainerType, name: String): List[String] = {
    val key = (entityId, cType, name)
    debug(s"Fetching attribute values of $key from hazelcast")
    hazelcast.get(key)
  }

  /**
   * Stores a string attribute in the database using the connection of this database.
   * This new value does not replace existing attributes for this (entityId,cType,name) key.
   *
   * => this method reason about attributes as multi-valued
   */
  def storeAttribute(entityId: String, cType: AttributeContainerType, name: String, value: String): Unit = {
    val key = (entityId, cType, name)
    Option(hazelcast.get(key)) match {
      case None =>
        if (hazelcast.replace(key, null, List(value))) {
          // yay, we succeeded in storing the new value
        } else {
          // ow snap, someone else beat us to it => retry to append
          storeAttribute(entityId, cType, name, value)
        }
      case Some(list) =>
        val newValue = value :: list
        if (hazelcast.replace(key, list, newValue)) {
          // yay, we succeeded in storing the new value
        } else {
          // ow snap, someone else beat us to it => retry
          storeAttribute(entityId, cType, name, value)
        }
    }
  }

  /**
   * Updates a string attribute in the database using the connection of this database.
   *
   * => this method reasons about attributes as single-valued
   */
  def updateAttribute(entityId: String, cType: AttributeContainerType, name: String, value: String): Unit = {
    val key = (entityId, cType, name)
    hazelcast.put(key, List(value))
  }

}