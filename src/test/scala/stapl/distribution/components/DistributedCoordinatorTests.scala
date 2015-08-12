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
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import akka.util.Timeout
import scala.util.{ Success, Failure }
import java.util.concurrent.CountDownLatch
import stapl.core.SUBJECT
import stapl.core.RESOURCE
import stapl.core.Permit
import akka.util.Timeout
import scala.concurrent.Await
import akka.pattern.ask

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

  val actorSystems = scala.collection.mutable.ListBuffer[ActorSystem]()
  val pool = new InMemoryAttributeDatabaseConnectionPool

  def setupCoordinators(policy: AbstractPolicy, nbCoordinators: Int): Option[(ActorSystem, CoordinatorLocater)] = {
    val clientSystem = startLocalActorSystem("STAPL-coordinator", 2552)
    actorSystems += clientSystem

    // set up the coordinator manager in the client system to which all the
    // coordinators will register
    val coordinatorManager = clientSystem.actorOf(Props(classOf[FixedNumberCoordinatorsDistributedCoordinatorManager], nbCoordinators), "distributed-coordinator-manager")

    // set up the coordinators
    val coordinatorLocations = (1 to nbCoordinators).map(id => ("127.0.0.1", 2553 + id))
    for (id <- 1 to nbCoordinators) {
      val system = startLocalActorSystem("STAPL-coordinator", 2553 + id)
      actorSystems += system

      // set up the coordinator
      /*DistributedCoordinator(policy: AbstractPolicy, nbWorkers: Int, nbUpdateWorkers: Int,
		  pool: AttributeDatabaseConnectionPool, coordinatorManager: ActorRef,
		  enableStatsIn: Boolean = false, enableStatsOut: Boolean = false, statsOutInterval: Int = 2000,
		  enableStatsWorkers: Boolean = false, enableStatsDb: Boolean = false,
		  mockDecision: Boolean = false, mockEvaluation: Boolean = false,
		  mockEvaluationDuration: Int = 0)*/
      val coordinator = system.actorOf(Props(classOf[DistributedCoordinator], policy,
        1, 1, pool, coordinatorManager,
        false, false, -1, false, false, false, false, -1), "coordinator")
    }

    // now construct the coordinatorLocater in the client system to reach
    // the coordinators
    // the implicits for later on
    implicit val dispatcher = clientSystem.dispatcher
    implicit val timeout = Timeout(5.second)
    // the locater to store the coordinators in later on
    val coordinatorLocater = new SimpleDistributedCoordinatorLocater
    Await.result(coordinatorManager ? ClientRegistrationProtocol.GetListOfCoordinators, 5.seconds) match {
      case ClientRegistrationProtocol.ListOfCoordinators(coordinators) =>
        // we will only receive this message when all coordinators have registered
        coordinatorLocater.setCoordinators(coordinators.map(_._2))
        if (coordinators.size == 0) {
          println("Received the list of coordinators, but no coordinators found. Shutting down")
          shutdownActorSystems
          return None
        }
        println(s"Successfully received the list of coordinators: $coordinators")
      case x =>
        println(s"Failed to get the list of coordinators, shutting down. Received result: $x")
        shutdownActorSystems
        return None
    }

    Some(clientSystem, coordinatorLocater)
  }

  def shutdownActorSystems = {
    actorSystems.foreach {
      case x =>
        x.shutdown
        x.awaitTermination(2.seconds) // to make it synchronous
    }
    actorSystems.clear
  }

  def resetDB(nbSubjects: Int, nbResources: Int) = {
    val db = pool.getConnection
    db.cleanStart
    // store the necessary attribute values
    for (i <- 1 to nbSubjects) {
      db.storeAttribute(s"subject$i", SUBJECT, subject.nbAccesses.name, 0)
    }
    for (i <- 1 to nbResources) {
      db.storeAttribute(s"resource$i", RESOURCE, resource.nbAccesses.name, 0)
    }
  }

  @Test def testSingleResource1 {
    val nbResources = 5

    for (nbSubjects <- 2 to 20; nbCoordinators <- 1 to 4) {
      resetDB(nbSubjects, nbResources)

      // set up the coordinators
      val (system, coordinatorLocater) = setupCoordinators(max1ResourceAccess, nbCoordinators).get

      // the implicits for later on
      implicit val dispatcher = system.dispatcher
      implicit val timeout = Timeout(5.second)

      // do the tests
      val latch = new CountDownLatch(nbSubjects)
      val nbPermits = new AtomicInteger(0)

      import ClientCoordinatorProtocol._
      for (i <- 1 to nbSubjects) {
        val request = AuthorizationRequest(s"subject$i", "view", "resource1")
        val result = coordinatorLocater.getCoordinatorFor(request) ? request
        println("sent request")
        result onComplete {
          case Success(AuthorizationDecision(id,decision)) =>
            latch.countDown()
            println(s"latch is ${latch.getCount()}")
            if (decision == Permit) {
              nbPermits.incrementAndGet()
            }
          case Success(x) =>
            fail(s"Unknown success received: $x")
          case Failure(e) =>
            fail("Error received", e)
        }
      }

      // wait for completion
      latch.await()
      assertEquals(1, nbPermits.get())

      shutdownActorSystems
    }
  }

  @Test def testSingleSubject1 {
    val nbSubjects = 5

    for (nbResources <- 2 to 20; nbCoordinators <- 1 to 4) {
      println("========================")
      println(s"Starting test nbSubjects=$nbSubjects, nbResources=$nbResources, nbCoordinators=$nbCoordinators")
      println("========================")
      resetDB(nbSubjects, nbResources)

      // set up the coordinators
      val (system, coordinatorLocater) = setupCoordinators(max1SubjectAccess, nbCoordinators).get

      // the implicits for later on
      implicit val dispatcher = system.dispatcher
      implicit val timeout = Timeout(5.second)

      val latch = new CountDownLatch(nbSubjects)
      val nbPermits = new AtomicInteger(0)

      import ClientCoordinatorProtocol._
      for (i <- 1 to nbSubjects) {
        val request = AuthorizationRequest("subject1", "view", s"resource$i")
        val result = coordinatorLocater.getCoordinatorFor(request) ? request
        println("sent request")
        result onComplete {
          case Success(AuthorizationDecision(id,decision)) =>
            latch.countDown()
            println(s"latch is ${latch.getCount()}")
            if (decision == Permit) {
              nbPermits.incrementAndGet()
            }
          case Success(x) =>
            fail(s"Unknown success received: $x")
          case Failure(e) =>
            fail("Error received", e)
        }
      }

      // wait for completion
      latch.await()
      assertEquals(1, nbPermits.get())

      shutdownActorSystems
    }
  }
}