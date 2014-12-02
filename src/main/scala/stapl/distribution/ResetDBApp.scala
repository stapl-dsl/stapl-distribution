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
  stapl.distribution.db.entities.ehealth.EntityManager().persist(db)
  stapl.distribution.db.entities.concurrency.EntityManager().persist(db)
  db.commit
  db.close
  info("Done")
}