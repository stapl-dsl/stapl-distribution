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

class LegacyAttributeDatabaseConnection(host: String, port: Int, database: String, username: String, password: String, autocommit: Boolean = true)
  extends AttributeDatabaseConnection with Logging {

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
      newconn.setAutoCommit(autocommit)
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
    if (!autocommit) {
      try {
        conn match {
          case Some(conn) => conn.commit()
          case None => throw new RuntimeException("The connection was not open, cannot commit.")
        }
      } catch {
        case e: SQLException => error("Cannot commit.", e)
      }
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
          getStringAttributeStmt = None
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
          getStringAttributeStmt = None
        }
        case None =>
      }
      conn match {
        case Some(c) => {
          c.close()
          conn = None
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

  /**
   * Fetches a string attribute from the database using the connection of this database.
   * Does NOT commit or close.
   */
  override def getStringAttribute(entityId: String, cType: AttributeContainerType, name: String): List[String] = {
    getStringAttributeStmt match {
      case None => throw new RuntimeException("The connection was not open, cannot fetch attribute.")
      case Some(stmt) => {
        var queryResult: ResultSet = null
        try {
          stmt.setString(1, entityId)
          stmt.setString(2, cType.toString())
          stmt.setString(3, name)
          queryResult = stmt.executeQuery()
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
   * Updates a string attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def updateAttribute(entityId: String, cType: AttributeContainerType, name: String, value: String): Unit = ???

}
