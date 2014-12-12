package stapl.distribution.components

import org.scalatest.junit.AssertionsForJUnit
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.Assert._
import stapl.core._
import stapl.core.Attribute
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.testkit.TestProbe

class ForemanManagerTest extends AssertionsForJUnit {
  
  implicit val system = ActorSystem("test-system")
  val probe = TestProbe()
  
  @Test def testIdle {
    val manager = new ForemanAdministration
    manager += probe.ref
    assertEquals(manager.idle, Set(probe.ref))
  }
}

class CoordinatorTests {

}