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

class AttributeDatabaseConnection(host: String, port: Int, database: String, username: String, password: String)
  extends Logging {

  private val dataSource = new ComboPooledDataSource
  dataSource.setMaxPoolSize(30); // no maximum
  dataSource.setMinPoolSize(1); // no maximum
  dataSource.setDriverClass("com.mysql.jdbc.Driver");
  dataSource.setUser(username);
  dataSource.setPassword(password);
  dataSource.setJdbcUrl(s"jdbc:mysql://$host:$port/$database");

  private var conn: Option[Connection] = None
  private var getStringAttributeStmt: Option[PreparedStatement] = None; // TODO better way for this? Option is overkill...
//  private var getSupportedXACMLAttributeIdsStmt: Option[PreparedStatement] = None;
  private var storeAttributeStmt: Option[PreparedStatement] = None;

  /**
   * Sets up the connection to the database in read/write mode.
   * Autocommit is disabled for this connection, so know you have to commit yourself!
   */
  def open(): Unit = {
    open(false)
  }

  /**
   * Sets up the connection to the database in given mode.
   * Autocommit is disabled for this connection, so know you have to commit yourself!
   */
  def open(readOnly: Boolean): Unit = {
    try {
      val newconn = dataSource.getConnection()
      newconn.setReadOnly(readOnly)
      newconn.setAutoCommit(false)
      getStringAttributeStmt = Some(newconn.prepareStatement("SELECT * FROM attributes WHERE entity_id=? && attribute_container_type=? && attribute_key=?;"))
//      getSupportedXACMLAttributeIdsStmt = Some(newconn.prepareStatement("SELECT xacmlIdentifier FROM SP_ATTRTYPE"))
      storeAttributeStmt = Some(newconn.prepareStatement("INSERT INTO attributes VALUES (default, ?, ?, ?, ?);"))
      conn = Some(newconn)
    } catch {
      case e: SQLException => error("Cannot open connection.", e)
    }
  }

  /**
   * Commits all operations.
   */
  def commit(): Unit = {
    try {
      conn match {
        case Some(conn) => conn.commit()
        case None => throw new RuntimeException("The connection was not open, cannot commit.")
      }
    } catch {
      case e: SQLException => error("Cannot commit.", e)
    }
  }

  /**
   * Closes the connection to the database.
   */
  def close(): Unit = {
    try {
      getStringAttributeStmt match {
        case Some(stmt) => {
          stmt.close()
          AttributeDatabaseConnection.this.getStringAttributeStmt = None
        }
        case None =>
      }
//      getSupportedXACMLAttributeIdsStmt match {
//        case Some(stmt) => {
//          stmt.close()
//          this.getSupportedXACMLAttributeIdsStmt = None
//        }
//        case None =>
//      }
      storeAttributeStmt match {
        case Some(stmt) => {
          stmt.close()
          AttributeDatabaseConnection.this.getStringAttributeStmt = None
        }
        case None =>
      }
      conn match {
        case Some(conn) => {
          conn.close()
          AttributeDatabaseConnection.this.conn = None
        }
        case None => // nothing to do here, closing a closed connection is not an error
      }
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
      conn match {
        case Some(conn) => {
          val createTablesPS = conn.prepareStatement("CREATE TABLE `attributes` (\n" +
            "  `id` int(11) NOT NULL AUTO_INCREMENT,\n" +
            "  `entity_id` varchar(45) NOT NULL,\n" +
            "  `attribute_container_type` varchar(45) NOT NULL,\n" +
            "  `attribute_key` varchar(45) NOT NULL,\n" +
            "  `attribute_value` varchar(100) NOT NULL,\n" +
            "  PRIMARY KEY (`id`),\n" +
            "  KEY `index` (`entity_id`,`attribute_key`)\n" +
            ");")
          createTablesPS.execute()
          info("Successfully created tables.")
        }
        case None => throw new RuntimeException("The connection was not open, cannot create tables.")
      }

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
      conn match {
        case Some(conn) => {
          val dropDataPS = conn.prepareStatement("DROP TABLE attributes;")
          dropDataPS.execute()
          logger.info("Successfully dropped tables.")
        }
        case None => throw new RuntimeException("The connection was not open, cannot drop tables.")
      }
    } catch {
      case e: SQLException => warn(s"Cannot drop tables: ${e.getMessage()}") // no exception needed here
    }
  }

//  /**
//   * Fetches all supported XACML attribute ids from the database.
//   */
//  def getSupportedXACMLAttributeIds(): List[String] = {
//    var result = List[String]()
//    getSupportedXACMLAttributeIdsStmt match {
//      case None => throw new RuntimeException("The connection was not open, cannot fetch supported XACML attribute ids.")
//      case Some(stmt) => {
//        var queryResult: ResultSet = null
//        try {
//          queryResult = stmt.executeQuery() // TODO this cannot be the best way to implement this in Scala...
//          while (queryResult.next()) {
//            result ::= queryResult.getString("xacmlIdentifier")
//          }
//        } catch {
//          case e: SQLException => error("Could not fetch xacml attribute identifiers", e)
//        } finally {
//          if (queryResult != null) {
//            queryResult.close()
//          }
//        }
//      }
//    }
//    result
//  }

  /**
   * Fetches a string attribute from the database using the connection of this database.
   * Does NOT commit or close.
   */
  def getStringAttribute(entityId: String, cType: AttributeContainerType, name: String): List[String] = {    
    getStringAttributeStmt match {
      case None => throw new RuntimeException("The connection was not open, cannot fetch attribute.")
      case Some(stmt) => {
        var queryResult: ResultSet = null
        try {          
          stmt.setString(1, entityId)
          stmt.setString(2, cType.toString())
          stmt.setString(3, name)
          val queryResult = stmt.executeQuery()
          // process the result
          var r = List[String]()
          while (queryResult.next()) {
            r ::= queryResult.getString("attribute_value")
          }
          r
        } catch {
          case e: SQLException => {
            error("Could not fetch xacml attribute identifiers", e)
            throw new RuntimeException("The connection was not open, cannot fetch attribute.")
          }
        } finally {
          if (queryResult != null) {
            queryResult.close()
          }
        }
      }
    }
  }

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
  def storeAttribute(entityId: String, cType: AttributeContainerType, name: String, value: String): Unit = {
    try {
      storeAttributeStmt match {
        case Some(stmt) => {
          stmt.setString(1, entityId)
          stmt.setString(2, cType.toString())
          stmt.setString(3, name)
          stmt.setString(4, value)
          stmt.executeUpdate()
        }
        case None => throw new RuntimeException("The connection was not open, cannot store attribute.")
      }
    } catch {
      case e: SQLException => {
        error("Cannot execute query.", e)
        throw new RuntimeException(e)
      }
    }
  }
  
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