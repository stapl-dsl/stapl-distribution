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

class TransactionalMySQLAttributeDatabaseConnectionPool(host: String, port: Int, database: String, username: String, password: String, 
    readonly: Boolean = false)
  extends AttributeDatabaseConnectionPool with Logging {

  private val dataSource = new ComboPooledDataSource
  dataSource.setMaxPoolSize(100); 
  dataSource.setMinPoolSize(1); 
  dataSource.setDriverClass("com.mysql.jdbc.Driver");
  dataSource.setUser(username);
  dataSource.setPassword(password);
  dataSource.setJdbcUrl(s"jdbc:mysql://$host:$port/$database");
  
  override def getConnection() = new TransactionalAttributeDatabaseConnection(dataSource.getConnection(), readonly)

}
object TransactionalMySQLAttributeDatabaseConnectionPool {
  def apply(host: String, port: Int, database: String, username: String, password: String, readonly: Boolean = false) =
    new TransactionalMySQLAttributeDatabaseConnectionPool(host: String, port: Int, database: String, username: String, password: String, readonly)
}