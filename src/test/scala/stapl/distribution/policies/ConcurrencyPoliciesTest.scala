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
import stapl.distribution.db.entities.concurrency.ConcurrencyEntityManager
import stapl.distribution.db.AttributeDatabaseConnection
import stapl.core.pdp.AttributeFinder
import stapl.distribution.db.DatabaseAttributeFinderModule
import stapl.core.pdp.PDP
import stapl.distribution.db.HardcodedEnvironmentAttributeFinderModule
import stapl.distribution.db.LegacyAttributeDatabaseConnection
import stapl.distribution.db.AttributeUpdatesObligationServiceModule
import stapl.core.pdp.ObligationService
import stapl.distribution.db.AttributeDatabaseConnectionPool
import stapl.distribution.db.MySQLAttributeDatabaseConnectionPool

class ConcurrencyPoliciesTest extends AssertionsForJUnit {

  val em = ConcurrencyEntityManager()
  val maxNbAccessessPDP = new PDP(ConcurrencyPolicies.maxNbAccess)
  val chineseWallPDP = new PDP(ConcurrencyPolicies.chineseWall)

  import em._
  import ConcurrencyPolicies._

  val db = MySQLAttributeDatabaseConnectionPool("localhost", 3306, "stapl-attributes", "root", "root").getConnection
  val finder = new AttributeFinder
  finder += new DatabaseAttributeFinderModule(db)
  val maxNbAccessesPDPWithDb = new PDP(ConcurrencyPolicies.maxNbAccess, finder)
  val chineseWallPDPWithDb = new PDP(ConcurrencyPolicies.chineseWall, finder)

  val obligationService = new ObligationService
  obligationService += new AttributeUpdatesObligationServiceModule(db)
  val maxNbAccessesPDPWithDbAndObligations = new PDP(ConcurrencyPolicies.maxNbAccess, finder, obligationService)
  val chineseWallPDPWithDbAndObligations = new PDP(ConcurrencyPolicies.chineseWall, finder, obligationService)

  @Before def resetDb {
    db.cleanStart
    db.commit
    em.persist(db)
    db.commit    
  }
  
  @After def commit = db.commit

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
    assertEquals(List(ConcreteChangeAttributeObligationAction(resourceOfBank1.id, resource.nbAccesses, 2, Update)), result.obligationActions)
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
    assertEquals(List(ConcreteChangeAttributeObligationAction(resourceOfBank1.id, resource.nbAccesses, 1, Update)), result.obligationActions)
  }

  @Test def testMaxNbAccessesWithDbAndObligations1 {
    val result = maxNbAccessesPDPWithDbAndObligations.evaluate(subject1.id, "blabla", resourceOfBank1.id)
    assertEquals(Permit, result.decision)
    assertEquals(List(), result.obligationActions) // the obligation should be fulfilled
    assertEquals(List(1), db.getLongAttribute(resourceOfBank1.id, resource.nbAccesses.cType, resource.nbAccesses.name))
  }

  @Test def testMaxNbAccessesWithDbAndObligations2 {
    for (i <- 1 to 5) {
      val result = maxNbAccessesPDPWithDbAndObligations.evaluate(subject1.id, "blabla", resourceOfBank1.id)
      assertEquals(Permit, result.decision)
      assertEquals(List(), result.obligationActions) // the obligation should be fulfilled
      assertEquals(List(i), db.getLongAttribute(resourceOfBank1.id, resource.nbAccesses.cType, resource.nbAccesses.name))
    }
    val result = maxNbAccessesPDPWithDbAndObligations.evaluate(subject1.id, "blabla", resourceOfBank1.id)
    assertEquals(Deny, result.decision)
    assertEquals(List(), result.obligationActions)
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
    assertEquals(List(ConcreteChangeAttributeObligationAction(subject1.id, subject.history, "bank1", Append)), result.obligationActions)
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
    assertEquals(List(ConcreteChangeAttributeObligationAction(subject1.id, subject.history, "bank1", Append)), result.obligationActions)
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
    assertEquals(List(ConcreteChangeAttributeObligationAction(subject1.id, subject.history, "bank1", Append)), result.obligationActions)
  }

  @Test def testChineseWall2WithDb {
    val result = chineseWallPDPWithDb.evaluate(subject1.id, "blabla", resourceOfBank1.id)
    assertEquals(Permit, result.decision)
    assertEquals(List(ConcreteChangeAttributeObligationAction(subject1.id, subject.history, "bank1", Append)), result.obligationActions)
  }
}