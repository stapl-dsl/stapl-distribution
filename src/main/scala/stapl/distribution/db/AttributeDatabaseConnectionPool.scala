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

class AttributeDatabaseConnectionPool(host: String, port: Int, database: String, username: String, password: String)
  extends Logging {

  private val dataSource = new ComboPooledDataSource
  dataSource.setMaxPoolSize(30); // no maximum
  dataSource.setMinPoolSize(1); // no maximum
  dataSource.setDriverClass("com.mysql.jdbc.Driver");
  dataSource.setUser(username);
  dataSource.setPassword(password);
  dataSource.setJdbcUrl(s"jdbc:mysql://$host:$port/$database");
  
  def getConnection() = new SimpleAttributeDatabaseConnection(dataSource.getConnection())

}
object AttributeDatabaseConnectionPool {
  def apply(host: String, port: Int, database: String, username: String, password: String) =
    new AttributeDatabaseConnectionPool(host: String, port: Int, database: String, username: String, password: String)
}