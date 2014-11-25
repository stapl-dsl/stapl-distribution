package stapl.distribution.policies

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
import stapl.distribution.db.entities.concurrency.EntityManager
import stapl.distribution.db.AttributeDatabaseConnection
import stapl.core.pdp.AttributeFinder
import stapl.distribution.db.DatabaseAttributeFinderModule
import stapl.core.pdp.PDP
import stapl.distribution.db.HardcodedEnvironmentAttributeFinderModule
import stapl.distribution.db.LegacyAttributeDatabaseConnection

object ConcurrencyPoliciesTest extends AssertionsForJUnit {

  @BeforeClass def resetDB() {
    val db = new LegacyAttributeDatabaseConnection("localhost", 3306, "stapl-attributes", "root", "root")
    db.open
    db.cleanStart
    val em = EntityManager()
    em.persist(db)
    db.commit
    db.close
  }
}
class ConcurrencyPoliciesTest extends AssertionsForJUnit {

  val em = EntityManager()
  val maxNbAccessessPDP = new PDP(ConcurrencyPolicies.maxNbAccess)
  val chineseWallPDP = new PDP(ConcurrencyPolicies.chineseWall)

  import em._
  import ConcurrencyPolicies._

  val db = new LegacyAttributeDatabaseConnection("localhost", 3306, "stapl-attributes", "root", "root")
  db.open()
  val finder = new AttributeFinder
  finder += new DatabaseAttributeFinderModule(db)
  val maxNbAccessesPDPWithDb = new PDP(ConcurrencyPolicies.maxNbAccess, finder)
  val chineseWallPDPWithDb = new PDP(ConcurrencyPolicies.chineseWall, finder)

  // val obligationService = new 

  @Test def testMaxNbAccesses1 {
    val result = maxNbAccessessPDP.evaluate(subject1.id, "blabla", resourceOfBank1.id,
      subject.attribute1 -> "a-value",
      subject.attribute2 -> "a-value",
      subject.attribute3 -> "a-value",
      subject.attribute4 -> "a-value",
      subject.attribute5 -> "a-value",
      subject.attribute6 -> "a-value",
      subject.attribute7 -> "a-value",
      subject.attribute8 -> "a-value",
      subject.attribute9 -> "a-value",
      subject.attribute10 -> "a-value",
      subject.history -> List[String](),
      resource.nbAccesses -> 1)
    assertEquals(Permit, result.decision)
    assertEquals(List(ConcreteUpdateAttributeObligationAction(resourceOfBank1.id, resource.nbAccesses, 2)), result.obligationActions)
  }

  @Test def testMaxNbAccesses2 {
    val result = maxNbAccessessPDP.evaluate(subject1.id, "blabla", resourceOfBank1.id,
      subject.attribute1 -> "a-value",
      subject.attribute2 -> "a-value",
      subject.attribute3 -> "a-value",
      subject.attribute4 -> "a-value",
      subject.attribute5 -> "a-value",
      subject.attribute6 -> "a-value",
      subject.attribute7 -> "a-value",
      subject.attribute8 -> "a-value",
      subject.attribute9 -> "a-value",
      subject.attribute10 -> "a-value",
      subject.history -> List[String](),
      resource.nbAccesses -> 5)
    assertEquals(Deny, result.decision)
    assertEquals(List(), result.obligationActions)
  }

  @Test def testMaxNbAccessesWithDb1 {
    val result = maxNbAccessesPDPWithDb.evaluate(subject1.id, "blabla", resourceOfBank1.id)
    assertEquals(Permit, result.decision)
    assertEquals(List(ConcreteUpdateAttributeObligationAction(resourceOfBank1.id, resource.nbAccesses, 1)), result.obligationActions)
  }

  @Test def testChineseWall1 {
    val result = chineseWallPDP.evaluate(subject1.id, "blabla", resourceOfBank1.id,
      subject.attribute1 -> "a-value",
      subject.attribute2 -> "a-value",
      subject.attribute3 -> "a-value",
      subject.attribute4 -> "a-value",
      subject.attribute5 -> "a-value",
      subject.attribute6 -> "a-value",
      subject.attribute7 -> "a-value",
      subject.attribute8 -> "a-value",
      subject.attribute9 -> "a-value",
      subject.attribute10 -> "a-value",
      subject.history -> List[String](),
      resource.owner -> bank1)
    assertEquals(Permit, result.decision)
    assertEquals(List(ConcreteAppendAttributeObligationAction(subject1.id, subject.history, "bank1")), result.obligationActions)
  }

  @Test def testChineseWall2 {
    val result = chineseWallPDP.evaluate(subject1.id, "blabla", resourceOfBank1.id,
      subject.attribute1 -> "a-value",
      subject.attribute2 -> "a-value",
      subject.attribute3 -> "a-value",
      subject.attribute4 -> "a-value",
      subject.attribute5 -> "a-value",
      subject.attribute6 -> "a-value",
      subject.attribute7 -> "a-value",
      subject.attribute8 -> "a-value",
      subject.attribute9 -> "a-value",
      subject.attribute10 -> "a-value",
      subject.history -> List[String]("bank1"),
      resource.owner -> bank1)
    assertEquals(Permit, result.decision)
    assertEquals(List(ConcreteAppendAttributeObligationAction(subject1.id, subject.history, "bank1")), result.obligationActions)
  }

  @Test def testChineseWall3 {
    val result = chineseWallPDP.evaluate(subject1.id, "blabla", resourceOfBank1.id,
      subject.attribute1 -> "a-value",
      subject.attribute2 -> "a-value",
      subject.attribute3 -> "a-value",
      subject.attribute4 -> "a-value",
      subject.attribute5 -> "a-value",
      subject.attribute6 -> "a-value",
      subject.attribute7 -> "a-value",
      subject.attribute8 -> "a-value",
      subject.attribute9 -> "a-value",
      subject.attribute10 -> "a-value",
      subject.history -> List[String]("bank2"),
      resource.owner -> bank1)
    assertEquals(Deny, result.decision)
    assertEquals(List(), result.obligationActions)
  }

  @Test def testChineseWall1WithDb {
    val result = chineseWallPDPWithDb.evaluate(subject1.id, "blabla", resourceOfBank1.id)
    assertEquals(Permit, result.decision)
    assertEquals(List(ConcreteAppendAttributeObligationAction(subject1.id, subject.history, "bank1")), result.obligationActions)
  }

  @Test def testChineseWall2WithDb {
    val result = chineseWallPDPWithDb.evaluate(subject1.id, "blabla", resourceOfBank1.id)
    assertEquals(Permit, result.decision)
    assertEquals(List(ConcreteAppendAttributeObligationAction(subject1.id, subject.history, "bank1")), result.obligationActions)
  }
}