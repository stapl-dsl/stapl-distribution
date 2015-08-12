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
package stapl.distribution

import stapl.distribution.db.LegacyAttributeDatabaseConnection
import stapl.core.SUBJECT
import com.hazelcast.config.Config
import com.hazelcast.config.MapConfig
import com.hazelcast.config.MapStoreConfig
import stapl.distribution.db.AttributeMapStore
import com.hazelcast.core.Hazelcast
import stapl.core.AttributeContainerType
import com.hazelcast.core.IMap
import stapl.distribution.db.AttributeDatabaseConnectionPool
import java.util.Calendar
import java.text.SimpleDateFormat
import stapl.distribution.db.MySQLAttributeDatabaseConnectionPool

case class DBTestConfig(databaseIP: String = "not-provided", databasePort: Int = -1)
case class HazelcastDBMasterConfig(ownIP: String = "not-provided", databaseIP: String = "not-provided", databasePort: Int = -1)
case class HazelcastDBSlaveConfig(coordinatorIP: String = "not-provided", databaseIP: String = "not-provided", databasePort: Int = -1)

object DBWriteTest {
  def main(args: Array[String]) {

    val parser = new scopt.OptionParser[DBTestConfig]("scopt") {
      head("Database test: connect to the database directly and read the same value over and over")
      opt[String]("database-ip") required () action { (x, c) =>
        c.copy(databaseIP = x)
      } text ("The IP address of the machine on which the database containing the attributes is running.")
      opt[Int]("database-port") required () action { (x, c) =>
        c.copy(databasePort = x)
      } text ("The port on which the database containing the attributes is listening.")
      help("help") text ("prints this usage text")
    }
    // parser.parse returns Option[C]
    parser.parse(args, DBTestConfig()) map { config =>
      val pool = new MySQLAttributeDatabaseConnectionPool(config.databaseIP, config.databasePort, "stapl-attributes", "root", "root")
      val db = pool.getConnection
      while (true) {
        println("Press ENTER to update the database")
        readLine
        val now = Calendar.getInstance().getTime()
        val minuteFormat = new SimpleDateFormat("hh:mm:ss")
        val newValue = minuteFormat.format(now)
        db.updateAttribute("patient:maarten", SUBJECT, "roles", newValue)
        println("Done")
      }
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}

object DBReadTest {
  def main(args: Array[String]) {

    val parser = new scopt.OptionParser[DBTestConfig]("scopt") {
      head("Database test: connect to the database directly and read the same value over and over")
      opt[String]("database-ip") required () action { (x, c) =>
        c.copy(databaseIP = x)
      } text ("The IP address of the machine on which the database containing the attributes is running.")
      opt[Int]("database-port") required () action { (x, c) =>
        c.copy(databasePort = x)
      } text ("The port on which the database containing the attributes is listening.")
      help("help") text ("prints this usage text")
    }
    // parser.parse returns Option[C]
    parser.parse(args, DBTestConfig()) map { config =>
      val db = new LegacyAttributeDatabaseConnection(config.databaseIP, config.databasePort, "stapl-attributes", "root", "root")
      db.open
      println("Press ENTER to start reading")
      readLine
      while (true) {
        println("Database: " + db.getStringAttribute("patient:maarten", SUBJECT, "roles"))
      }
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}

object HazelcastReadTest {
  def main(args: Array[String]) {

    val parser = new scopt.OptionParser[HazelcastDBSlaveConfig]("scopt") {
      head("Database test: connect to the database directly and read the same value over and over")
      opt[String]("coordinator-ip") required () action { (x, c) =>
        c.copy(coordinatorIP = x)
      } text ("The IP address of the coordinator (Hazelcast).")
      opt[String]("database-ip") required () action { (x, c) =>
        c.copy(databaseIP = x)
      } text ("The IP address of the machine on which the database containing the attributes is running.")
      opt[Int]("database-port") required () action { (x, c) =>
        c.copy(databasePort = x)
      } text ("The port on which the database containing the attributes is listening.")
      help("help") text ("prints this usage text")
    }
    // parser.parse returns Option[C]
    parser.parse(args, HazelcastDBSlaveConfig()) map { config =>
      // set up hazelcast db connection
      val MAP_NAME = "stapl-attributes"
      val cfg = new Config();
      cfg.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false)
      cfg.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true).addMember(config.coordinatorIP)
      val mapCfg = new MapConfig(MAP_NAME)
      mapCfg.setMapStoreConfig(new MapStoreConfig()
        .setEnabled(true)
        .setImplementation(new AttributeMapStore(config.databaseIP, config.databasePort, "stapl-attributes", "root", "root")))
      cfg.addMapConfig(mapCfg)
      val instance = Hazelcast.newHazelcastInstance(cfg);
      val hazelcast: IMap[(String, AttributeContainerType, String), List[String]] = instance.getMap(MAP_NAME)
      println("Press ENTER to start reading")
      readLine
      while (true) {
        println("Hazelcast: " + hazelcast.get("patient:maarten", SUBJECT, "roles"))
      }
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}

object HazelcastWriteTest {
  def main(args: Array[String]) {

    val parser = new scopt.OptionParser[HazelcastDBMasterConfig]("scopt") {
      head("Database test: connect to the database directly and read the same value over and over")
      opt[String]("own-ip") required () action { (x, c) =>
        c.copy(ownIP = x)
      } text ("The public IP address of the machine on which this application is running (required for accepting connection requests).")
      opt[String]("database-ip") required () action { (x, c) =>
        c.copy(databaseIP = x)
      } text ("The IP address of the machine on which the database containing the attributes is running.")
      opt[Int]("database-port") required () action { (x, c) =>
        c.copy(databasePort = x)
      } text ("The port on which the database containing the attributes is listening.")
      help("help") text ("prints this usage text")
    }
    // parser.parse returns Option[C]
    parser.parse(args, HazelcastDBMasterConfig()) map { config =>
      // set up hazelcast db connection
      val MAP_NAME = "stapl-attributes"
      val cfg = new Config()
      cfg.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false)
      cfg.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true)
      cfg.getNetworkConfig().getInterfaces().setEnabled(true).addInterface(config.ownIP)
      val mapCfg = new MapConfig(MAP_NAME)
      mapCfg.setMapStoreConfig(new MapStoreConfig()
        .setEnabled(true)
        .setImplementation(new AttributeMapStore(config.databaseIP, config.databasePort, "stapl-attributes", "root", "root")))
      cfg.addMapConfig(mapCfg)
      val instance = Hazelcast.newHazelcastInstance(cfg);
      val hazelcast: IMap[(String, AttributeContainerType, String), List[String]] = instance.getMap(MAP_NAME)

      // go go go      
      while (true) {
        println("Press ENTER to update hazelcast")
        readLine
        val now = Calendar.getInstance().getTime()
        val minuteFormat = new SimpleDateFormat("hh:mm:ss")
        val newValue = minuteFormat.format(now)
        hazelcast.set(("patient:maarten", SUBJECT, "roles"), List(newValue))
        println("Done")
      }
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}

