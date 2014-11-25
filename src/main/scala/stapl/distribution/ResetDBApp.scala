package stapl.distribution

import stapl.distribution.db.AttributeDatabaseConnection
import stapl.distribution.db.entities.EntityManager
import grizzled.slf4j.Logging
import stapl.distribution.db.LegacyAttributeDatabaseConnection

object ResetDBApp extends App with Logging {
  
  val db = new LegacyAttributeDatabaseConnection("localhost", 3306, "stapl-attributes", "root", "root")
  db.open
  info("Resetting databases")
  db.cleanStart
  info("Persisting entity data")
  EntityManager().persist(db)
  db.commit
  db.close
  info("Done")
}