package stapl.distribution.db

import org.scalatest.junit.AssertionsForJUnit
import org.junit.Before
import org.junit.After
import org.junit.BeforeClass
import org.junit.Test
import org.junit.Assert._
import stapl.core.pdp.EvaluationCtx
import org.joda.time.LocalDateTime
import stapl.core._
import stapl.core.Attribute
import stapl.distribution.db.entities.EntityManager
import stapl.core.pdp.AttributeFinder
import stapl.core.pdp.PDP
import stapl.examples.policies.EhealthPolicy
import scala.collection.mutable.ListBuffer
import stapl.core.pdp.ObligationService

class MockAttributeDatabaseConnection extends AttributeDatabaseConnection("", -1, "", "", "") {

  override def open() = {
    // do nothing
  }

  override def close() = {
    // do nothing
  }

  val receivedAttributeUpdates = ListBuffer[(String, Attribute, Any)]()

  override def storeAnyAttribute(entityId: String, attribute: Attribute, value: Any) = {
    receivedAttributeUpdates += new Tuple3(entityId, attribute, value)
  }
}

class UpdateAttributesTest extends AssertionsForJUnit with BasicPolicy {

  subject.count = SimpleAttribute(Number)

  val policy = Rule("update-attribute") := permit iff (true) performing (update(subject.count, subject.count + 5))

  var db: MockAttributeDatabaseConnection = null
  var pdp: PDP = null

  @Before def openDB = {
    // reset the mock db every time
    db = new MockAttributeDatabaseConnection
    val obligationService = new ObligationService
    obligationService += new AttributeUpdatesObligationServiceModule(db)
    pdp = new PDP(policy, new AttributeFinder, obligationService)
  }

  @Test def testAttributeUpdates {
    val result = pdp.evaluate("maarten", "view", "doc123", subject.count -> 12)
    println(result)
    assertEquals(List(("maarten",subject.count,17)), db.receivedAttributeUpdates)
  }
}