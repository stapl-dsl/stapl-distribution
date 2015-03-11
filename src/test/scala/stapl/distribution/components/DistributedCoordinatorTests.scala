package stapl.distribution.components

import org.scalatest.junit.AssertionsForJUnit
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.Assert._
import akka.pattern.ask
import akka.actor.ActorRef
import akka.actor.ActorLogging
import akka.actor.Actor
import akka.actor.Terminated
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import akka.actor.Props
import scala.collection.mutable.{ Map, Queue }
import com.hazelcast.config.Config
import com.hazelcast.config.MapConfig
import com.hazelcast.config.MapStoreConfig
import stapl.distribution.db.AttributeMapStore
import com.hazelcast.core.Hazelcast
import stapl.distribution.db.AttributeDatabaseConnectionPool
import stapl.distribution.db.HazelcastAttributeDatabaseConnectionPool
import stapl.distribution.db.MySQLAttributeDatabaseConnectionPool
import stapl.examples.policies.EhealthPolicy
import stapl.distribution.policies.ConcurrencyPolicies
import stapl.distribution.db.MockAttributeDatabaseConnectionPool
import stapl.distribution.db.InMemoryAttributeDatabaseConnectionPool
import stapl.distribution.components.ClientCoordinatorProtocol.AuthorizationRequest
import stapl.core.AbstractPolicy
import stapl.core._
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import akka.util.Timeout
import scala.util.{ Success, Failure }
import java.util.concurrent.CountDownLatch

class DistributedCoordinatorTest extends AssertionsForJUnit {
  
  import stapl.distribution.policies.ConcurrencyPolicies._

  private def startLocalActorSystem(name: String, port: Int) = {
    val defaultConf = ConfigFactory.load()
    val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = 127.0.0.1
        akka.remote.netty.tcp.port = $port
        akka.loglevel = DEBUG
      """).withFallback(defaultConf)
    ActorSystem(name, customConf)
  }

  val NB_COORDINATORS = 2
  val NB_SUBJECTS = 5
  val NB_RESOURCES = 5

  val actorSystems = scala.collection.mutable.ListBuffer[ActorSystem]()
  val pool = new InMemoryAttributeDatabaseConnectionPool

  def setupCoordinators(policy: AbstractPolicy) = {
    val clientSystem = startLocalActorSystem("STAPL-coordinator", 2552)
    actorSystems += clientSystem

    // set up the coordinators
    val coordinatorLocations = (1 to NB_COORDINATORS).map(id => ("127.0.0.1", 2553 + id))
    val coordinatorManagers = scala.collection.mutable.ListBuffer[HardcodedDistributedCoordinatorManager]()
    for (id <- 1 to NB_COORDINATORS) {
      val system = startLocalActorSystem("STAPL-coordinator", 2553 + id)
      actorSystems += system
      val coordinatorManager = new HardcodedDistributedCoordinatorManager(system, coordinatorLocations: _*)
      coordinatorManagers += coordinatorManager
      
      // set up the coordinator
      /*DistributedCoordinator(coordinatorId: Long, policy: AbstractPolicy, nbWorkers: Int, nbUpdateWorkers: Int,
		  pool: AttributeDatabaseConnectionPool, coordinatorManager: CoordinatorLocater,
		  enableStatsIn: Boolean = false, enableStatsOut: Boolean = false, statsOutInterval: Int = 2000,
		  enableStatsWorkers: Boolean = false, enableStatsDb: Boolean = false,
		  mockDecision: Boolean = false, mockEvaluation: Boolean = false,
		  mockEvaluationDuration: Int = 0)*/
      val coordinator = system.actorOf(Props(classOf[DistributedCoordinator], id.toLong, policy,
        1, 1, pool, coordinatorManager,
        false, false, -1, false, false, false, false, -1), "coordinator")
    }
    
    // only now initialize the coordinator managers
    coordinatorManagers.foreach(_.initialize)

    // set up the client
    val coordinators = new HardcodedDistributedCoordinatorManager(clientSystem, coordinatorLocations: _*)
    coordinators.initialize

    (clientSystem, coordinators)
  }

  def shutdownActorSystems = {
    actorSystems.foreach(_.shutdown)
    actorSystems.clear
  }

  @Before def resetDB = {
    val db = pool.getConnection
    db.cleanStart
    // store the necessary attribute values
    for (i <- 1 to NB_SUBJECTS) {
      db.storeAttribute(s"subject$i", SUBJECT, subject.nbAccesses.name, 0)
    }
    for (i <- 1 to NB_RESOURCES) {
      db.storeAttribute(s"resource$i", RESOURCE, resource.nbAccesses.name, 0)
    }
  }

  @Test def testSingleResource1 {
    val (system, coordinators) = setupCoordinators(max1ResourceAccess)

    val latch = new CountDownLatch(NB_SUBJECTS)
    val nbPermits = new AtomicInteger(0)

    implicit val timeout = Timeout(2.second)
    implicit val ec = system.dispatcher

    import ClientCoordinatorProtocol._
    for (i <- 1 to NB_SUBJECTS) {
      val request = AuthorizationRequest(s"subject$i", "view", "resource1")
      val result = coordinators.getCoordinatorFor(request) ? request
      println("sent request")
      result onComplete {
        case Success(AuthorizationDecision(decision)) =>
          latch.countDown()
          println(s"latch is ${latch.getCount()}")
          if (decision == Permit) {
            nbPermits.incrementAndGet()
          }
        case Success(x) =>
          print("damn1")
          fail(s"Unknown success received: $x")
        case Failure(e) =>
          println("received error: ")
          e.printStackTrace()
          fail("Error received", e)
      }
    }
    
    // wait for completion
    latch.await()
    println("wtf")
    assertEquals(nbPermits.get(), 1)
    
    actorSystems.foreach(_.shutdown)
  }
}