package stapl.distribution

import stapl.core.pdp.PDP
import stapl.examples.policies.EhealthPolicy
import stapl.core.pdp.EvaluationCtx
import stapl.core.pdp.BasicEvaluationCtx
import stapl.core.pdp.RequestCtx
import stapl.distribution.db.entities.ehealth.EhealthEntityManager
import stapl.core.pdp.AttributeFinder
import stapl.distribution.db.HardcodedEnvironmentAttributeFinderModule
import stapl.distribution.db.DatabaseAttributeFinderModule
import com.mchange.v2.c3p0.ComboPooledDataSource
import stapl.distribution.db.AttributeDatabaseConnection
import stapl.core.pdp.RemoteEvaluator
import scala.util.{ Success, Failure }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.util.Timeout
import stapl.distribution.db.LegacyAttributeDatabaseConnection

object ConcurrentAsyncTest extends App {

  import EhealthPolicy._

  val pdp = new PDP(naturalPolicy)

  val em = new EhealthEntityManager()

  val username = "root"
  val password = "root"
  val host = "localhost"
  val port = 3306
  val database = "stapl-attributes"

  def test(i: Int) = {
    val finder = new AttributeFinder
    finder += new HardcodedEnvironmentAttributeFinderModule
    val conn = new LegacyAttributeDatabaseConnection(host, port, database, username, password)
    conn.open()
    finder += new DatabaseAttributeFinderModule(conn)

    val ctx = new BasicEvaluationCtx(
      s"$i", new RequestCtx(em.maarten.id, "view", em.maartenStatus.id),
      finder, new RemoteEvaluator)

//    println(pdp.evaluate(ctx))
    
    ???

//    pdp.evaluateAsync(ctx).onComplete({ x => 
//      conn.close
//      x match {
//        case Success(r) => println(s"success: $r")
//        case Failure(e) =>
//          println(s"failure: $e")
//          e.printStackTrace()
//      }
//    })

    //    implicit val timeout = Timeout(2 seconds)
    //    
    //    
    //    Await.ready(f, 3 seconds).onComplete {
    //      _ match {
    //        case Success(r) => println(s"success: $r")
    //        case Failure(e) => println(s"failure: $e")
    //      }
    //    }
  }

  for (i <- 1 to 10) {
    test(i)
  }

}