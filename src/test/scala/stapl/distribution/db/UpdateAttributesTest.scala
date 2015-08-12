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
import stapl.distribution.db.entities.ehealth.EhealthEntityManager
import stapl.core.pdp.AttributeFinder
import stapl.core.pdp.PDP
import stapl.examples.policies.EhealthPolicy
import scala.collection.mutable.ListBuffer
import stapl.core.pdp.ObligationService
import scala.collection.mutable.MultiMap

class UpdateAttributesTest extends AssertionsForJUnit with BasicPolicy {
  
  import stapl.core.dsl._

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