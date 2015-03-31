package stapl.distribution

import stapl.examples.policies.EhealthPolicy
import stapl.core.pdp.PDP
import stapl.core.pdp.AttributeFinder
import stapl.core.pdp.RequestCtx
import stapl.core.Result
import stapl.core.NotApplicable
import stapl.core.Deny
import stapl.core.Permit
import stapl.core.dsl.log
import stapl.core.ConcreteValue
import stapl.core.Attribute
import stapl.distribution.util.Timer
import org.joda.time.LocalDateTime
import stapl.distribution.db.entities.ehealth.EhealthEntityManager
import stapl.distribution.db.MySQLAttributeDatabaseConnectionPool
import stapl.distribution.db.DatabaseAttributeFinderModule
import stapl.distribution.db.SimpleAttributeDatabaseConnection
import grizzled.slf4j.Logging
import stapl.distribution.db.AttributeDatabaseConnectionPool
import stapl.core.Decision
import stapl.distribution.db.entities.ehealth.Person
import stapl.distribution.db.HardcodedEnvironmentAttributeFinderModule

object SimplePDPPerformanceTest extends App with Logging {

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
    import EhealthPolicy._
    val em = new EhealthEntityManager

    val pool = new MySQLAttributeDatabaseConnectionPool(config.databaseIP, config.databasePort, "stapl-attributes", "root", "root")

    // reset the db
    resetDB(pool)

    // set up the PDP
    val attributeFinder = new AttributeFinder()
    attributeFinder += new DatabaseAttributeFinderModule(pool.getConnection)
    attributeFinder += new HardcodedEnvironmentAttributeFinderModule
    val pdp = new PDP(naturalPolicy, attributeFinder)

    // do the tests
    val nbRuns = 1
    val timer = new Timer()
    for ((request, shouldBe) <- em.requests) {
      for (i <- 0 to nbRuns) {
        implicit val (subject, action, resource, extraAttributes) = request
        val r = timer time {
          pdp.evaluate(subject.id, action, resource.id, extraAttributes: _*)
        }
        assert(shouldBe, r)
        info("===================================================")
      }
    }
    println(s"Mean after $nbRuns runs = ${timer.mean} ms")
  } getOrElse {
    // arguments are bad, error message will have been displayed
  }

  def resetDB(pool: MySQLAttributeDatabaseConnectionPool) {
    val db = pool.getConnection
    info("Resetting databases")
    db.cleanStart
    info("Persisting entity data")
    EhealthEntityManager().persist(db)
    db.commit
    db.close
    info("Done")
  }

  def assert(wanted: Result, actual: Result)(implicit subject: Person, action: String, resource: stapl.distribution.db.entities.ehealth.Resource, extraAttributes: List[(stapl.core.Attribute, stapl.core.ConcreteValue)]) = {
    if (wanted.decision != actual.decision) {
      throw new AssertionError(s"Wanted ${wanted.decision} but was ${actual.decision} for request (${subject.id},$action,${resource.id},$extraAttributes)")
    } else {
      info(s"Request (${subject.id},$action,${resource.id},$extraAttributes) OK")
    }
  }
}

