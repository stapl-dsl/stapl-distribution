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
import stapl.distribution.db.entities.ehealth.EhealthEntityManager
import stapl.distribution.db.AttributeDatabaseConnection
import stapl.core.pdp.AttributeFinder
import stapl.distribution.db.DatabaseAttributeFinderModule
import stapl.core.pdp.PDP
import stapl.examples.policies.EhealthPolicy
import stapl.distribution.db.HardcodedEnvironmentAttributeFinderModule
import stapl.distribution.db.LegacyAttributeDatabaseConnection

object PolicyTest extends AssertionsForJUnit {

  @BeforeClass def resetDB() {
    val db = new LegacyAttributeDatabaseConnection("localhost", 3306, "stapl-attributes", "root", "root")
    db.open
    db.cleanStart
    val em = EhealthEntityManager()
    em.persist(db)
    db.commit
    db.close
  }
}
class PolicyTest extends AssertionsForJUnit {

  val db = new LegacyAttributeDatabaseConnection("localhost", 3306, "stapl-attributes", "root", "root")
  val em = EhealthEntityManager()
  val finder = new AttributeFinder
  finder += new DatabaseAttributeFinderModule(db)
  finder += new HardcodedEnvironmentAttributeFinderModule
  val pdp = new PDP(EhealthPolicy.naturalPolicy, finder)
  
  import em._
  
  @Before def openDB = db.open
  
  @After def closeDB = db.close
  
  @Test def testCardiologist1 {
    assertEquals(Deny, pdp.evaluate(cardiologist1, "view", maartenStatus).decision)
  }
  
  @Test def testCardiologist1ToBart {
    assertEquals(Deny, pdp.evaluate(cardiologist1, "view", bartStatus).decision)
  }
  
  @Test def testCardiologist1ToWouter {
    assertEquals(Permit, pdp.evaluate(cardiologist1, "view", wouterStatus).decision)
  }
  
  @Test def testCardiologist2 {
    assertEquals(Permit, pdp.evaluate(cardiologist2, "view", maartenStatus).decision)
  }
  
  @Test def testCardiologist3 {
    assertEquals(Permit, pdp.evaluate(cardiologist3, "view", maartenStatus).decision)
  }
  
  @Test def testCardiologist4 {
    assertEquals(Deny, pdp.evaluate(cardiologist4, "view", maartenStatus).decision)
  }
  
  @Test def testCardiologistHead {
    assertEquals(Permit, pdp.evaluate(cardiologistHead, "view", maartenStatus).decision)
  }
  
  @Test def testCardiologistTriggered {
    //assertEquals(pdp.evaluate(cardiologistTriggered, "view", maartenStatus))
    assertEquals(Permit, pdp.evaluate(cardiologistTriggered, "view", maartenStatus).decision)
  }
  
  @Test def testEmergencySpecialist1 {
    assertEquals(Deny, pdp.evaluate(emergencySpecialist1, "view", maartenStatus).decision)
  }
  
  @Test def testEmergencySpecialist1ToBart {
    assertEquals(Permit, pdp.evaluate(emergencySpecialist1, "view", bartStatus).decision)
  }
  
  @Test def testEmergencySpecialist1ToWouter {
    assertEquals(Permit, pdp.evaluate(emergencySpecialist1, "view", wouterStatus).decision)
  }
  
  @Test def testGP1 {
    assertEquals(Permit, pdp.evaluate(gp1, "view", maartenStatus).decision)
  }
  
  @Test def testGP2 {
    assertEquals(Permit, pdp.evaluate(gp2, "view", maartenStatus).decision)
  }
  
  @Test def testGP3 {
    assertEquals(Permit, pdp.evaluate(gp3, "view", maartenStatus).decision)
  }
  
  @Test def testGP4 {
    assertEquals(Deny, pdp.evaluate(gp4, "view", maartenStatus).decision)
  }
  
  @Test def testGPHasConsultation {
    assertEquals(Permit, pdp.evaluate(gpHasConsultation, "view", maartenStatus).decision)
  }
  
  @Test def testOncologist1 {
    assertEquals(Deny, pdp.evaluate(oncologist1, "view", maartenStatus).decision)
  }
  
  @Test def testCardiologyNurse1 {
    assertEquals(Deny, pdp.evaluate(cardiologyNurse1, "view", maartenStatus).decision)
  }
  
  @Test def testCardiologyNurse2 {
    assertEquals(Deny, pdp.evaluate(cardiologyNurse2, "view", maartenStatus).decision)
  }
  
  @Test def testCardiologyNurse3 {
    assertEquals(Permit, pdp.evaluate(cardiologyNurse3, "view", maartenStatus).decision)
  }
  
  @Test def testCardiologyNurse3ToBart {
    assertEquals(Deny, pdp.evaluate(cardiologyNurse3, "view", bartStatus).decision)
  }
  
  @Test def testCardiologyNurse3ToErna {
    assertEquals(Deny, pdp.evaluate(cardiologyNurse3, "view", ernaStatus).decision)
  }
  
  @Test def testCardiologyNurse3ToWouter {
    assertEquals(Permit, pdp.evaluate(cardiologyNurse3, "view", wouterStatus).decision)
  }
  
  @Test def testElderCareNurse1 {
    assertEquals(Deny, pdp.evaluate(elderCareNurse1, "view", maartenStatus).decision)
  }
  
  @Test def testElderCareNurse2 {
    assertEquals(Deny, pdp.evaluate(elderCareNurse2, "view", maartenStatus).decision)
  }
  
  @Test def testOncologyNurse {
    assertEquals(Deny, pdp.evaluate(oncologyNurse, "view", maartenStatus).decision)
  }
  
  @Test def testMaarten {
    // Permit because allowed to access the PMS and own data
    assertEquals(Permit, pdp.evaluate(maarten, "view", maartenStatus).decision)
  }
  
  @Test def testMaartenToWouter {
    // Deny because not own data
    assertEquals(Deny, pdp.evaluate(maarten, "view", wouterStatus).decision)
  }
  
  @Test def testWouterToWouter {
    // Deny because not allowed to access the PMS
    assertEquals(Deny, pdp.evaluate(wouter, "view", wouterStatus).decision)
  }
}