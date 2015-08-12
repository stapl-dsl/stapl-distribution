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

object ResetDBApp extends App with Logging {
  
  val db = new LegacyAttributeDatabaseConnection("localhost", 3306, "stapl-attributes", "root", "root", false /* not autocommit */)
  db.open
  info("Resetting databases")
  db.cleanStart
  info("Persisting entity data")
  stapl.distribution.db.entities.ehealth.EhealthEntityManager(true).persist(db) // the LARGE set of entities
  stapl.distribution.db.entities.concurrency.ConcurrencyEntityManager().persist(db)
  db.commit
  db.close
  info("Done")
}