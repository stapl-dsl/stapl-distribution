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
import stapl.distribution.db.entities.ehealth.EntityManager
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
    val em = EntityManager()
    em.persist(db)
    db.commit
    db.close
  }
}
class PolicyTest extends AssertionsForJUnit {

  val db = new LegacyAttributeDatabaseConnection("localhost", 3306, "stapl-attributes", "root", "root")
  val em = EntityManager()
  val finder = new AttributeFinder
  finder += new DatabaseAttributeFinderModule(db)
  finder += new HardcodedEnvironmentAttributeFinderModule
  val pdp = new PDP(EhealthPolicy.naturalPolicy, finder)
  
  import em._
  
  @Before def openDB = db.open
  
  @After def closeDB = db.close
  
  @Test def testCardiologist1 {
    assertEquals(Result(Deny), pdp.evaluate(cardiologist1, "view", maartenStatus))
  }
  
  @Test def testCardiologist1ToBart {
    assertEquals(Result(Deny), pdp.evaluate(cardiologist1, "view", bartStatus))
  }
  
  @Test def testCardiologist1ToWouter {
    assertEquals(Result(Permit), pdp.evaluate(cardiologist1, "view", wouterStatus))
  }
  
  @Test def testCardiologist2 {
    assertEquals(Result(Permit), pdp.evaluate(cardiologist2, "view", maartenStatus))
  }
  
  @Test def testCardiologist3 {
    assertEquals(Result(Permit), pdp.evaluate(cardiologist3, "view", maartenStatus))
  }
  
  @Test def testCardiologist4 {
    assertEquals(Result(Deny), pdp.evaluate(cardiologist4, "view", maartenStatus))
  }
  
  @Test def testCardiologistHead {
    assertEquals(Result(Permit), pdp.evaluate(cardiologistHead, "view", maartenStatus))
  }
  
  @Test def testCardiologistTriggered {
    //assertEquals(pdp.evaluate(cardiologistTriggered, "view", maartenStatus))
    assertEquals(Permit, pdp.evaluate(cardiologistTriggered, "view", maartenStatus).decision)
  }
  
  @Test def testEmergencySpecialist1 {
    assertEquals(Result(Deny), pdp.evaluate(emergencySpecialist1, "view", maartenStatus))
  }
  
  @Test def testEmergencySpecialist1ToBart {
    assertEquals(Result(Permit), pdp.evaluate(emergencySpecialist1, "view", bartStatus))
  }
  
  @Test def testEmergencySpecialist1ToWouter {
    assertEquals(Result(Permit), pdp.evaluate(emergencySpecialist1, "view", wouterStatus))
  }
  
  @Test def testGP1 {
    assertEquals(Result(Permit), pdp.evaluate(gp1, "view", maartenStatus))
  }
  
  @Test def testGP2 {
    assertEquals(Result(Permit), pdp.evaluate(gp2, "view", maartenStatus))
  }
  
  @Test def testGP3 {
    assertEquals(Result(Permit), pdp.evaluate(gp3, "view", maartenStatus))
  }
  
  @Test def testGP4 {
    assertEquals(Result(Deny), pdp.evaluate(gp4, "view", maartenStatus))
  }
  
  @Test def testGPHasConsultation {
    assertEquals(Result(Permit), pdp.evaluate(gpHasConsultation, "view", maartenStatus))
  }
  
  @Test def testOncologist1 {
    assertEquals(Result(Deny), pdp.evaluate(oncologist1, "view", maartenStatus))
  }
  
  @Test def testCardiologyNurse1 {
    assertEquals(Result(Deny), pdp.evaluate(cardiologyNurse1, "view", maartenStatus))
  }
  
  @Test def testCardiologyNurse2 {
    assertEquals(Result(Deny), pdp.evaluate(cardiologyNurse2, "view", maartenStatus))
  }
  
  @Test def testCardiologyNurse3 {
    assertEquals(Result(Permit), pdp.evaluate(cardiologyNurse3, "view", maartenStatus))
  }
  
  @Test def testCardiologyNurse3ToBart {
    assertEquals(Result(Deny), pdp.evaluate(cardiologyNurse3, "view", bartStatus))
  }
  
  @Test def testCardiologyNurse3ToErna {
    assertEquals(Result(Deny), pdp.evaluate(cardiologyNurse3, "view", ernaStatus))
  }
  
  @Test def testCardiologyNurse3ToWouter {
    assertEquals(Result(Permit), pdp.evaluate(cardiologyNurse3, "view", wouterStatus))
  }
  
  @Test def testElderCareNurse1 {
    assertEquals(Result(Deny), pdp.evaluate(elderCareNurse1, "view", maartenStatus))
  }
  
  @Test def testElderCareNurse2 {
    assertEquals(Result(Deny), pdp.evaluate(elderCareNurse2, "view", maartenStatus))
  }
  
  @Test def testOncologyNurse {
    assertEquals(Result(Deny), pdp.evaluate(oncologyNurse, "view", maartenStatus))
  }
  
  @Test def testMaarten {
    // Permit because allowed to access the PMS and own data
    assertEquals(Result(Permit), pdp.evaluate(maarten, "view", maartenStatus))
  }
  
  @Test def testMaartenToWouter {
    // Deny because not own data
    assertEquals(Result(Deny), pdp.evaluate(maarten, "view", wouterStatus))
  }
  
  @Test def testWouterToWouter {
    // Deny because not allowed to access the PMS
    assertEquals(Result(Deny), pdp.evaluate(wouter, "view", wouterStatus))
  }
}