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
import stapl.distribution.db.entities.EntityManager

case class SimplePDPPerformanceTestConfig(databaseIP: String = "not-provided", databasePort: Int = -1,
  nbRuns: Int = -1)

object SimplePDPPerformanceTest extends App with Logging {

  val parser = new scopt.OptionParser[SimplePDPPerformanceTestConfig]("scopt") {

    head("Database test: connect to the database directly and read the same value over and over")

    opt[String]("database-ip") required () action { (x, c) =>
      c.copy(databaseIP = x)
    } text ("The IP address of the machine on which the database containing the attributes is running.")

    opt[Int]("database-port") required () action { (x, c) =>
      c.copy(databasePort = x)
    } text ("The port on which the database containing the attributes is listening.")

    opt[Int]("nb-runs") required () action { (x, c) =>
      c.copy(nbRuns = x)
    } text ("The number of runs to do for each request.")

    help("help") text ("prints this usage text")
  }
  // parser.parse returns Option[C]
  parser.parse(args, SimplePDPPerformanceTestConfig()) map { config =>
    import EhealthPolicy._
    //val em = new EhealthEntityManager
    val em = new EhealthEntityManager(true)

    val pool = new MySQLAttributeDatabaseConnectionPool(config.databaseIP, config.databasePort, "stapl-attributes", "root", "root")

    // set up the PDP
    val attributeFinder = new AttributeFinder()
    attributeFinder += new DatabaseAttributeFinderModule(pool.getConnection)
    attributeFinder += new HardcodedEnvironmentAttributeFinderModule
    val pdp = new PDP(naturalPolicy, attributeFinder)

    // do the tests: iterate over the different requests in order to avoid JVM optimizations
    val timers = em.requests.map(x => {
      val (subject, action, resource, extraAttributes) = x._1
      (x._1, new Timer(s"Request (${subject.id},$action,${resource.id},$extraAttributes)"))
    }).toMap
    for (i <- 0 to config.nbRuns) {
      println(s"Run #$i/${config.nbRuns}")
      for ((request, shouldBe) <- em.requests) {
        implicit val (subject, action, resource, extraAttributes) = request
        val timer = timers(request)
        val r = timer time {
          pdp.evaluate(subject.id, action, resource.id, extraAttributes: _*)
        }
        assert(shouldBe, r)
      }
    }
    println(f"# Average over all requests = ${timers.values.map(_.mean).foldLeft(0.0)((a,b) => a + b) / timers.size.toDouble}%2.2f ms")
    println("#! Results")
    timers.values.foreach(x => println(x.toJSON()))
  } getOrElse {
    // arguments are bad, error message will have been displayed
  }

  def assert(wanted: Result, actual: Result)(implicit subject: Person, action: String, resource: stapl.distribution.db.entities.ehealth.Resource, extraAttributes: List[(stapl.core.Attribute, stapl.core.ConcreteValue)]) = {
    if (wanted.decision != actual.decision) {
      throw new AssertionError(s"Wanted ${wanted.decision} but was ${actual.decision} for request (${subject.id},$action,${resource.id},$extraAttributes)")
    } else {
      debug(s"Request (${subject.id},$action,${resource.id},$extraAttributes) OK")
    }
  }
}

