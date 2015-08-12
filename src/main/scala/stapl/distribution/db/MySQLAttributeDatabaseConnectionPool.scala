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

class MySQLAttributeDatabaseConnectionPool(host: String, port: Int, database: String, username: String, password: String, 
    readonly: Boolean = false)
  extends AttributeDatabaseConnectionPool with Logging {

  private val dataSource = new ComboPooledDataSource
  dataSource.setMaxPoolSize(100); 
  dataSource.setMinPoolSize(1); 
  dataSource.setDriverClass("com.mysql.jdbc.Driver");
  dataSource.setUser(username);
  dataSource.setPassword(password);
  dataSource.setJdbcUrl(s"jdbc:mysql://$host:$port/$database");
  
  override def getConnection() = new SimpleAttributeDatabaseConnection(dataSource.getConnection(), readonly)

}
object MySQLAttributeDatabaseConnectionPool {
  def apply(host: String, port: Int, database: String, username: String, password: String, readonly: Boolean = false) =
    new MySQLAttributeDatabaseConnectionPool(host: String, port: Int, database: String, username: String, password: String, readonly)
}