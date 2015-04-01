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
 * The trait for all connection pools.
 */
trait AttributeDatabaseConnectionPool {
  
  def getConnection(): AttributeDatabaseConnection
}

/**
 *
 * Constructor: sets up an *open* AttributeDatabaseConnection.
 */
abstract class AttributeDatabaseConnection extends Logging {
  
  /**
   * Closes this attribute database connection.
   */
  def close()

  /**
   * Fetches a string attribute from the database using the connection of this database.
   * Does NOT commit or close.
   */
  def getStringAttribute(entityId: String, cType: AttributeContainerType, name: String): List[String]

  /**
   * Fetches a string attribute from the database using the connection of this database.
   * Does NOT commit or close.
   */
  def getStringAttribute(entityId: String, attribute: Attribute): List[String] = {
    // TODO should we check the type here?
    getStringAttribute(entityId, attribute.cType, attribute.name)
  }

  /**
   * Fetches an integer attribute from the database using the connection of this database.
   * Does NOT commit or close.
   */
  def getLongAttribute(entityId: String, cType: AttributeContainerType, name: String): List[Long] = {
    val strings = getStringAttribute(entityId, cType, name);
    strings.map(s => s.toLong)
  }

  /**
   * Fetches a integer attribute from the database using the connection of this database.
   * Does NOT commit or close.
   */
  def getIntegerAttribute(entityId: String, attribute: Attribute): List[Long] = {
    // TODO should we check the type here?
    getLongAttribute(entityId, attribute.cType, attribute.name)
  }

  /**
   * Fetches a boolean attribute from the database using the connection of this database.
   * Does NOT commit or close.
   */
  def getBooleanAttribute(entityId: String, cType: AttributeContainerType, name: String): List[Boolean] = {
    val strings = getStringAttribute(entityId, cType, name);
    strings.map(s => if (s == "true") true else false)
  }

  /**
   * Fetches a boolean attribute from the database using the connection of this database.
   * Does NOT commit or close.
   */
  def getBooleanAttribute(entityId: String, attribute: Attribute): List[Boolean] = {
    // TODO should we check the type here?
    getBooleanAttribute(entityId, attribute.cType, attribute.name)
  }

  /**
   * Fetches a date attribute from the database using the connection of this database.
   * Does NOT commit or close.
   */
  def getDateAttribute(entityId: String, cType: AttributeContainerType, name: String): List[LocalDateTime] = {
    val strings = getStringAttribute(entityId, cType, name);
    var r = List[LocalDateTime]()
    for (s <- strings) { // can't do this with a map because of the possible parsing exception
      try {
        val d = new LocalDateTime(s)
        r ::= d
      } catch {
        case e: IllegalArgumentException => warn(s"Parsing exception when parsing $s", e)
      }
    }
    r
  }

  /**
   * Fetches a date attribute from the database using the connection of this database.
   * Does NOT commit or close.
   */
  def getDateAttribute(entityId: String, attribute: Attribute): List[LocalDateTime] = {
    // TODO should we check the type here?
    getDateAttribute(entityId, attribute.cType, attribute.name)
  }

  /**
   * Convenience method for storing attributes: any type of data can be given and this
   * method will try to cast it depending on the attribute type of the given attribute.
   */
  def storeAnyAttribute(entityId: String, attribute: Attribute, value: Any): Unit = {
    attribute.aType match {
      case String => storeAttribute(entityId, attribute, value.asInstanceOf[java.lang.String])
      case Number => storeAttribute(entityId, attribute, value.asInstanceOf[Long])
      case Bool => storeAttribute(entityId, attribute, value.asInstanceOf[Boolean])
      case _ => ??? // TODO
    }
  }

  /**
   * Stores a string attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def storeAttribute(entityId: String, cType: AttributeContainerType, name: String, value: String): Unit

  /**
   * Stores a string attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def storeAttribute(entityId: String, attribute: Attribute, value: String): Unit = {
    // TODO do we need to check for the type of the attribute here?
    storeAttribute(entityId, attribute.cType, attribute.name, value)
  }

  /**
   * Stores a long attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def storeAttribute(entityId: String, cType: AttributeContainerType, name: String, value: Int): Unit = {
    storeAttribute(entityId, cType, name, "" + value)
  }

  /**
   * Convenience method for storing attributes.
   * Stores a long attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def storeAttribute(entityId: String, attribute: Attribute, value: Int): Unit = {
    // TODO do we need to check for the type of the attribute here?
    storeAttribute(entityId, attribute.cType, attribute.name, value)
  }

  /**
   * Stores a long attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def storeAttribute(entityId: String, cType: AttributeContainerType, name: String, value: Long): Unit = {
    storeAttribute(entityId, cType, name, "" + value)
  }

  /**
   * Convenience method for storing attributes.
   * Stores a long attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def storeAttribute(entityId: String, attribute: Attribute, value: Long): Unit = {
    // TODO do we need to check for the type of the attribute here?
    storeAttribute(entityId, attribute.cType, attribute.name, value)
  }

  /**
   * Stores a boolean attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def storeAttribute(entityId: String, cType: AttributeContainerType, name: String, value: Boolean): Unit = {
    storeAttribute(entityId, cType, name, if (value) "true" else "false")
  }

  /**
   * Convenience method for storing attributes.
   * Stores a string attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def storeAttribute(entityId: String, attribute: Attribute, value: Boolean): Unit = {
    // TODO do we need to check for the type of the attribute here?
    storeAttribute(entityId, attribute.cType, attribute.name, value)
  }

  /**
   * Stores a string attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def storeAttribute(entityId: String, cType: AttributeContainerType, name: String, value: LocalDateTime): Unit = {
    val v = value.toString()
    storeAttribute(entityId, cType, name, v)
  }

  /**
   * Convenience method for storing attributes.
   * Stores a string attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def storeAttribute(entityId: String, attribute: Attribute, value: LocalDateTime): Unit = {
    // TODO do we need to check for the type of the attribute here?
    storeAttribute(entityId, attribute.cType, attribute.name, value)
  }

  /**
   * Stores a set of string attributes in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def storeAttribute(entityId: String, cType: AttributeContainerType, name: String, value: List[String]): Unit = {
    for (s <- value) yield {
      storeAttribute(entityId, cType, name, s);
    }
  }

  /**
   * Convenience method for storing attributes.
   * Stores a string attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def storeAttribute(entityId: String, attribute: Attribute, value: List[String]): Unit = {
    // TODO do we need to check for the type of the attribute here?
    storeAttribute(entityId, attribute.cType, attribute.name, value)
  }

  // TODO implement the other List methods

  /**
   * Convenience method for updating attributes: any type of data can be given and this
   * method will try to cast it depending on the attribute type of the given attribute.
   */
  def updateAnyAttribute(entityId: String, attribute: Attribute, value: Any): Unit = {
    attribute.aType match {
      case String => updateAttribute(entityId, attribute, value.asInstanceOf[java.lang.String])
      case Number => updateAttribute(entityId, attribute, value.asInstanceOf[Long])
      case Bool => updateAttribute(entityId, attribute, value.asInstanceOf[Boolean])
      case _ => ??? // TODO
    }
  }

  /**
   * Updates a string attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def updateAttribute(entityId: String, cType: AttributeContainerType, name: String, value: String): Unit

  /**
   * Updates a string attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def updateAttribute(entityId: String, attribute: Attribute, value: String): Unit = {
    // TODO do we need to check for the type of the attribute here?
    updateAttribute(entityId, attribute.cType, attribute.name, value)
  }

  /**
   * Updates an integer attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def updateAttribute(entityId: String, cType: AttributeContainerType, name: String, value: Int): Unit = {
    updateAttribute(entityId, cType, name, "" + value)
  }

  /**
   * Updates a long attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def updateAttribute(entityId: String, cType: AttributeContainerType, name: String, value: Long): Unit = {
    updateAttribute(entityId, cType, name, "" + value)
  }

  /**
   * Updates a string attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def updateAttribute(entityId: String, attribute: Attribute, value: Long): Unit = {
    // TODO do we need to check for the type of the attribute here?
    updateAttribute(entityId, attribute.cType, attribute.name, value)
  }

  /**
   * Updates a boolean attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def updateAttribute(entityId: String, cType: AttributeContainerType, name: String, value: Boolean): Unit = {
    updateAttribute(entityId, cType, name, if (value) "true" else "false")
  }

  /**
   * Updates a string attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def updateAttribute(entityId: String, attribute: Attribute, value: Boolean): Unit = {
    // TODO do we need to check for the type of the attribute here?
    updateAttribute(entityId, attribute.cType, attribute.name, value)
  }

  /**
   * Updates a string attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def updateAttribute(entityId: String, cType: AttributeContainerType, name: String, value: LocalDateTime): Unit = {
    val v = value.toString()
    updateAttribute(entityId, cType, name, v)
  }

}