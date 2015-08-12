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

import stapl.distribution.db.AttributeDatabaseConnection
import grizzled.slf4j.Logging
import stapl.distribution.db.LegacyAttributeDatabaseConnection

case class ResetDBAppConfig(databaseIP: String = "not-provided", databasePort: Int = -1)

object ResetDBApp extends App with Logging {

  val parser = new scopt.OptionParser[ResetDBAppConfig]("ResetDBApp") {
    head("STAPL - Reset database")

    opt[String]("database-ip") required () action { (x, c) =>
      c.copy(databaseIP = x)
    } text ("The IP address of the machine on which the database containing the attributes is running.")

    opt[Int]("database-port") required () action { (x, c) =>
      c.copy(databasePort = x)
    } text ("The port on which the database containing the attributes is listening.")
  }
  // parser.parse returns Option[C]
  parser.parse(args, ResetDBAppConfig()) map { config =>
    val db = new LegacyAttributeDatabaseConnection(config.databaseIP, config.databasePort, "stapl-attributes", "root", "root", false /* not autocommit */ )
    db.open
    info("Resetting databases")
    db.cleanStart
    info("Persisting entity data")
    stapl.distribution.db.entities.ehealth.EhealthEntityManager(true).persist(db) // the LARGE set of entities
    stapl.distribution.db.entities.concurrency.ConcurrencyEntityManager().persist(db)
    db.commit
    db.close
    info("Done")
  } getOrElse {
    // arguments are bad, error message will have been displayed
  }
}