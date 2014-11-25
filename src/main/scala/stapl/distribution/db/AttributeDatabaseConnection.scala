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
 * Constructor: sets up an open AttributeDatabaseConnection.
 */
abstract class AttributeDatabaseConnection extends Logging {

  /**
   * Commits all operations.
   */
  def commit(): Unit

  /**
   * Closes the connection to the database.
   */
  def close(): Unit

  def cleanStart(): Unit

  /**
   * Opens a connection, creates the tables, commits and closes the connection.
   */
  def createTables(): Unit

  /**
   * Opens a connection, drops the data, commits and closes the connection.
   */
  def dropData(): Unit

  /**
   * Fetches a string attribute from the database using the connection of this database.
   * Does NOT commit or close.
   */
  def getStringAttribute(entityId: String, cType: AttributeContainerType, name: String): List[String]

  /**
   * Fetches an integer attribute from the database using the connection of this database.
   * Does NOT commit or close.
   */
  def getIntegerAttribute(entityId: String, cType: AttributeContainerType, name: String): List[Int] = {
    val strings = getStringAttribute(entityId, cType, name);
    strings.map(s => s.toInt)
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
   * Convenience method for storing attributes: any type of data can be given and this
   * method will try to cast it depending on the attribute type of the given attribute.
   */
  def storeAnyAttribute(entityId: String, attribute: Attribute, value: Any): Unit = {
    attribute.aType match {
      case String => storeAttribute(entityId, attribute, value.asInstanceOf[java.lang.String])
      case Number => storeAttribute(entityId, attribute, value.asInstanceOf[Int])
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
   * Stores an integer attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def storeAttribute(entityId: String, cType: AttributeContainerType, name: String, value: Int): Unit = {
    storeAttribute(entityId, cType, name, "" + value)
  }

  /**
   * Convenience method for storing attributes.
   * Stores a string attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def storeAttribute(entityId: String, attribute: Attribute, value: Int): Unit = {
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

}