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
class TransactionalAttributeDatabaseConnection(initialConnection: Connection, readonly: Boolean = true) 
		extends SimpleAttributeDatabaseConnection(initialConnection, readonly) with Logging {

  conn.setAutoCommit(false)

  /**
   * Commits all operations.
   */
  override def commit(): Unit = {
    conn.commit()
  }
}