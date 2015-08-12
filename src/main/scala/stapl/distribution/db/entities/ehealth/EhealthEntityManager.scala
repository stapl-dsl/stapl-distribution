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
package stapl.distribution.db.entities.ehealth

import grizzled.slf4j.Logging
import org.joda.time.LocalDateTime
import stapl.distribution.db.AttributeDatabaseConnection
import stapl.core.ConcreteValue
import stapl.core.Result
import stapl.core.Attribute
import stapl.core.Permit
import stapl.core.Deny
import stapl.core.Decision
import stapl.distribution.db.entities.DateHelper
import stapl.distribution.db.entities.Entity
import stapl.distribution.db.entities.SubjectEntity
import stapl.distribution.db.entities.ResourceEntity
import stapl.distribution.db.entities.ResourceEntity
import stapl.distribution.db.entities.ResourceEntity
import scala.util.Random
import stapl.distribution.db.entities.EntityManager
import stapl.distribution.components.ClientCoordinatorProtocol.AuthorizationRequest

object EhealthEntityManager {
  def apply(createArtificialEntities: Boolean = false, nbArtificialEntityGroups: Int = 100) = new EhealthEntityManager(createArtificialEntities, nbArtificialEntityGroups)
}
class EhealthEntityManager(createArtificialEntities: Boolean = false, nbArtificialEntityGroups: Int = 100) extends EntityManager with Logging {

  override val entities = scala.collection.mutable.Map[String, Entity]()
  override val subjects = scala.collection.mutable.Map[String, SubjectEntity]()
  override val resources = scala.collection.mutable.Map[String, ResourceEntity]()
  
  private val CARDIOLOGY = "cardiology"
  private val ELDER_CARE = "elder_care"

  implicit def entity2id(e: Entity): String = e.id
  implicit def decision2Result(d: Decision): Result = Result(d)

  private val d = new DateHelper

  val requests = scala.collection.mutable.Map[(Person, String, Resource, List[(Attribute, ConcreteValue)]), Result]()

  /**
   * Override randomRequest: do not return a request consisting of a random subject
   * and random resource, but return a random request from the set of valid e-health
   * requests.
   */
  override def randomRequest = {    
    val rs = requests.keySet
    val (subject,action,resource,rest) = rs.toVector(Random.nextInt(rs.size))
    AuthorizationRequest(subject.id,action,resource.id)
  }
  
  /*
   * Create the normal entities 
   */ 
  // The main physicians of the test
  val cardiologistHead = createCardiologist(
    "physician:cardiologist:head", true, false, randomId())
  val cardiologist1 = createCardiologist(
    "physician:cardiologist:1", false, false, randomId())
  val cardiologist2 = createCardiologist(
    "physician:cardiologist:2", false, false, randomId())
  val cardiologist3 = createCardiologist(
    "physician:cardiologist:3", false, false, randomId())
  val cardiologist4 = createCardiologist(
    "physician:cardiologist:4", false, false, randomId())
  val cardiologistTriggered = createCardiologist(
    "physician:cardiologist:triggered", false, true, randomId())
  val gp1 = createGP("physician:gp:1", false, randomId())
  val gp2 = createGP("physician:gp:2", false, randomId())
  val gp3 = createGP("physician:gp:3", false, randomId())
  val gp4 = createGP("physician:gp:4", false, randomId())
  val elderCareSpecialist1 = createElderCareSpecialist(
    "physician:elder_care:1", false, false, randomId())
  val elderCareSpecialist2 = createElderCareSpecialist(
    "physician:elder_care:2", false, false, randomId())
  val elderCareSpecialist3 = createElderCareSpecialist(
    "physician:elder_care:3", false, false, randomId())
  val emergencySpecialist1 = createEmergencySpecialist(
    "physician:emergency:1", false,
    false, randomId())
  val oncologist1 = createOncologist("physician:oncologist:1", false, false, randomId())

  // The main nurses of the test
  val oncologyNurse = createNurse("nurse:oncology:1", "oncology", false, "hospital", d.normalShiftStart(), d.normalShiftStop())
  val cardiologyNurse1 = createNurse("nurse:cardiology:1", CARDIOLOGY, true, "gone", d.normalShiftStart(), d.normalShiftStop())
  val cardiologyNurse2 = createNurse("nurse:cardiology:2", CARDIOLOGY, true, "hospital", d.earlyShiftStart(), d.earlyShiftStop())
  val cardiologyNurse3 = createNurse("nurse:cardiology:3", CARDIOLOGY, true, "hospital", d.normalShiftStart(), d.normalShiftStop())
  val elderCareNurse1 = createNurse("nurse:elder_care:1", ELDER_CARE, false, "hospital", d.normalShiftStart(), d.normalShiftStop())
  val elderCareNurse2 = createNurse("nurse:elder_care:2", ELDER_CARE, true, "hospital", d.normalShiftStart(), d.normalShiftStop())

  // The main patients of the test
  // Maarten: discharged two weeks ago, has access to the PMS, has
  // withdrawn consent for cardiologist1. GP1 is responsible for Maarten.
  val maarten = createDischargedPatient("patient:maarten", true,
    d.threeDaysAgo())
  val maartenStatus = createPatientStatus(
    "patientstatus:of:maarten", maarten, d.daysAgo(2), "good",
    false, false)
  // Wouter: not discharged, does not have access to the PMS
  val wouter = createDischargedPatient("patient:wouter", false, d.yesterday())
  // No physician has triggered breaking glass, no operator has triggered
  // emergency, but wouterStatus does indicate emergency.
  val wouterStatus = createPatientStatus(
    "patientstatus:of:wouter", wouter, d.daysAgo(3), "blabla", true,
    false)
  // Bart: not discharged, does not have access to the PMS
  val bart = createNondischargedPatient("patient:bart", false)
  // Bart's status is bad, but no emergency
  val bartStatus = createPatientStatus(
    "patientstatus:of:bart", wouter, d.twoWeeksAgo(), "bad", false,
    false)
  // Erna:
  val erna = createDischargedPatient("patient:erna", false, d.twoWeeksAgo())
  val ernaStatus = createPatientStatus(
    "patientstatus:of:erna", erna, d.twoWeeksAgo(), "blabla", false,
    false)

  // Some more relationships:
  // 1. Cardiologist 1 has recently treated Maarten...
  cardiologist1.treated ::= maarten
  cardiologist1.treatedByTeam ::= maarten
  cardiologist1.treatedInLastSixMonths ::= maarten
  // 2. ...but Maarten has withdrawn consent for Cardiologist1
  maartenStatus.ownerWithdrawnConsents ::= cardiologist1
  // 3. GP1 is responsible for Maarten
  maartenStatus.ownerResponsiblePhysicians ::= gp1
  // 4. Cardiologist2 has also treated Maarten recently
  cardiologist2.treated ::= maarten
  cardiologist2.treatedByTeam ::= maarten
  cardiologist2.treatedInLastSixMonths ::= maarten
  // 5. Cardiologist3 is in the same team as Cardiologist2
  cardiologist3.treatedByTeam ::= maarten
  // 6. Cardiologist4 has no relationship to a patient
  //
  // 7. Maarten is in the care unit of ElderCareSpecialist1
  elderCareSpecialist1.admittedPatientsInCareUnit ::= maarten
  // 8. Maarten has been treated by ElderCareSpecialist2 in the last six
  // months
  elderCareSpecialist2.treatedInLastSixMonths ::= maarten
  // 9. ElderCareSpecialist3 has no relationship to a patient
  //
  // 10. EmergencyCareSpecialist1 has relationship to a patient
  //
  // 11. Maarten is a primary patient of GP2
  gp2.primaryPatients ::= maarten
  // 12. GP3 has recently treated Maarten
  gp3.treatedInLastSixMonths ::= maarten
  // 13. Maarten is on consultation with gpHasConsultation
  val gpHasConsultation = createGP("physician:gp:has-consultation", false, maarten.id)
  // 14. GP4 has no relationship to a patient
  // 
  // 15. Oncologist1 has recently treated Maarten
  oncologist1.treated ::= maarten
  oncologist1.treatedByTeam ::= maarten
  oncologist1.treatedInLastSixMonths ::= maarten
  // 16. OncologyNurse1 has no relationship to a patient 
  // 
  // 17. CardologyNurse1 has no relationship to a patient 
  // 
  // 18. CardologyNurse2 has no relationship to a patient 
  //
  // 19. Maarten is/was in the care unit of CardologyNurse3 
  cardiologyNurse3.patientsAdmittedInNurseUnit ::= maarten
  // 20. Wouter is/was in the care unit of CardologyNurse3 
  cardiologyNurse3.patientsAdmittedInNurseUnit ::= wouter
  // 21. Erna is/was in the care unit of CardologyNurse3 
  cardiologyNurse3.patientsAdmittedInNurseUnit ::= erna
  // 22. ElderCareNurse1 has no relationship to a patient
  //
  // 23. Maarten is admitted in the nurse unit of ElderCareNurse2
  // and she is responsible for Maarten
  elderCareNurse2.patientsAdmittedInNurseUnit ::= maarten
  elderCareNurse2.responsiblePatients ::= maarten

  /**
   * ********************************
   * THE REQUESTS
   * ********************************
   */
  requests((cardiologist1, "view", maartenStatus, List())) = Deny
  requests((cardiologist1, "view", bartStatus, List())) = Deny
  requests((cardiologist1, "view", wouterStatus, List())) = Permit
  requests((cardiologist2, "view", maartenStatus, List())) = Permit
  requests((cardiologist3, "view", maartenStatus, List())) = Permit
  requests((cardiologist4, "view", maartenStatus, List())) = Deny
  requests((cardiologistHead, "view", maartenStatus, List())) = Permit
  requests((cardiologistTriggered, "view", maartenStatus, List())) = Permit
  requests((emergencySpecialist1, "view", maartenStatus, List())) = Deny
  requests((emergencySpecialist1, "view", bartStatus, List())) = Permit
  requests((emergencySpecialist1, "view", wouterStatus, List())) = Permit
  requests((gp1, "view", maartenStatus, List())) = Permit
  requests((gp2, "view", maartenStatus, List())) = Permit
  requests((gp3, "view", maartenStatus, List())) = Permit
  requests((gp4, "view", maartenStatus, List())) = Deny
  requests((gpHasConsultation, "view", maartenStatus, List())) = Permit
  requests((oncologist1, "view", maartenStatus, List())) = Deny
  requests((cardiologyNurse1, "view", maartenStatus, List())) = Deny
  requests((cardiologyNurse2, "view", maartenStatus, List())) = Deny
  requests((cardiologyNurse3, "view", maartenStatus, List())) = Permit
  requests((cardiologyNurse3, "view", bartStatus, List())) = Deny
  requests((cardiologyNurse3, "view", ernaStatus, List())) = Deny
  requests((cardiologyNurse3, "view", wouterStatus, List())) = Permit
  requests((elderCareNurse1, "view", maartenStatus, List())) = Deny
  requests((elderCareNurse2, "view", maartenStatus, List())) = Permit
  requests((oncologyNurse, "view", maartenStatus, List())) = Deny
  requests((maarten, "view", maartenStatus, List())) = Permit
  requests((maarten, "view", wouterStatus, List())) = Deny
  requests((wouter, "view", wouterStatus, List())) = Deny

  /*
   * Create the artifical entities
   */ 
  if (createArtificialEntities) {
    for (i <- 1 to nbArtificialEntityGroups) {
      createArtificialEntities(s"-artificial-$i")
    }
  }

  private def createArtificialEntities(suffix: String) {
    // The main physicians of the test
    val cardiologistHead = createCardiologist(
      s"physician:cardiologist:head$suffix", true, false, randomId())
    val cardiologist1 = createCardiologist(
      s"physician:cardiologist:1$suffix", false, false, randomId())
    val cardiologist2 = createCardiologist(
      s"physician:cardiologist:2$suffix", false, false, randomId())
    val cardiologist3 = createCardiologist(
      s"physician:cardiologist:3$suffix", false, false, randomId())
    val cardiologist4 = createCardiologist(
      s"physician:cardiologist:4$suffix", false, false, randomId())
    val cardiologistTriggered = createCardiologist(
      s"physician:cardiologist:triggered$suffix", false, true, randomId())
    val gp1 = createGP(s"physician:gp:1$suffix", false, randomId())
    val gp2 = createGP(s"physician:gp:2$suffix", false, randomId())
    val gp3 = createGP(s"physician:gp:3$suffix", false, randomId())
    val gp4 = createGP(s"physician:gp:4$suffix", false, randomId())
    val elderCareSpecialist1 = createElderCareSpecialist(
      s"physician:elder_care:1$suffix", false, false, randomId())
    val elderCareSpecialist2 = createElderCareSpecialist(
      s"physician:elder_care:2$suffix", false, false, randomId())
    val elderCareSpecialist3 = createElderCareSpecialist(
      s"physician:elder_care:3$suffix", false, false, randomId())
    val emergencySpecialist1 = createEmergencySpecialist(
      s"physician:emergency:1$suffix", false,
      false, randomId())
    val oncologist1 = createOncologist(s"physician:oncologist:1$suffix", false, false, randomId())

    // The main nurses of the test
    val oncologyNurse = createNurse(s"nurse:oncology:1$suffix", "oncology", false, "hospital", d.normalShiftStart(), d.normalShiftStop())
    val cardiologyNurse1 = createNurse(s"nurse:cardiology:1$suffix", CARDIOLOGY, true, "gone", d.normalShiftStart(), d.normalShiftStop())
    val cardiologyNurse2 = createNurse(s"nurse:cardiology:2$suffix", CARDIOLOGY, true, "hospital", d.earlyShiftStart(), d.earlyShiftStop())
    val cardiologyNurse3 = createNurse(s"nurse:cardiology:3$suffix", CARDIOLOGY, true, "hospital", d.normalShiftStart(), d.normalShiftStop())
    val elderCareNurse1 = createNurse(s"nurse:elder_care:1$suffix", ELDER_CARE, false, "hospital", d.normalShiftStart(), d.normalShiftStop())
    val elderCareNurse2 = createNurse(s"nurse:elder_care:2$suffix", ELDER_CARE, true, "hospital", d.normalShiftStart(), d.normalShiftStop())

    // The main patients of the test
    // Maarten: discharged two weeks ago, has access to the PMS, has
    // withdrawn consent for cardiologist1. GP1 is responsible for Maarten.
    val maarten = createDischargedPatient(s"patient:maarten$suffix", true,
      d.threeDaysAgo())
    val maartenStatus = createPatientStatus(
      s"patientstatus:of:maarten$suffix", maarten, d.daysAgo(2), "good",
      false, false)
    // Wouter: not discharged, does not have access to the PMS
    val wouter = createDischargedPatient(s"patient:wouter$suffix", false, d.yesterday())
    // No physician has triggered breaking glass, no operator has triggered
    // emergency, but wouterStatus does indicate emergency.
    val wouterStatus = createPatientStatus(
      s"patientstatus:of:wouter$suffix", wouter, d.daysAgo(3), "blabla", true,
      false)
    // Bart: not discharged, does not have access to the PMS
    val bart = createNondischargedPatient(s"patient:bart$suffix", false)
    // Bart's status is bad, but no emergency
    val bartStatus = createPatientStatus(
      s"patientstatus:of:bart$suffix", wouter, d.twoWeeksAgo(), "bad", false,
      false)
    // Erna:
    val erna = createDischargedPatient(s"patient:erna$suffix", false, d.twoWeeksAgo())
    val ernaStatus = createPatientStatus(
      s"patientstatus:of:erna$suffix", erna, d.twoWeeksAgo(), "blabla", false,
      false)

    // Some more relationships:
    // 1. Cardiologist 1 has recently treated Maarten...
    cardiologist1.treated ::= maarten
    cardiologist1.treatedByTeam ::= maarten
    cardiologist1.treatedInLastSixMonths ::= maarten
    // 2. ...but Maarten has withdrawn consent for Cardiologist1
    maartenStatus.ownerWithdrawnConsents ::= cardiologist1
    // 3. GP1 is responsible for Maarten
    maartenStatus.ownerResponsiblePhysicians ::= gp1
    // 4. Cardiologist2 has also treated Maarten recently
    cardiologist2.treated ::= maarten
    cardiologist2.treatedByTeam ::= maarten
    cardiologist2.treatedInLastSixMonths ::= maarten
    // 5. Cardiologist3 is in the same team as Cardiologist2
    cardiologist3.treatedByTeam ::= maarten
    // 6. Cardiologist4 has no relationship to a patient
    //
    // 7. Maarten is in the care unit of ElderCareSpecialist1
    elderCareSpecialist1.admittedPatientsInCareUnit ::= maarten
    // 8. Maarten has been treated by ElderCareSpecialist2 in the last six
    // months
    elderCareSpecialist2.treatedInLastSixMonths ::= maarten
    // 9. ElderCareSpecialist3 has no relationship to a patient
    //
    // 10. EmergencyCareSpecialist1 has relationship to a patient
    //
    // 11. Maarten is a primary patient of GP2
    gp2.primaryPatients ::= maarten
    // 12. GP3 has recently treated Maarten
    gp3.treatedInLastSixMonths ::= maarten
    // 13. Maarten is on consultation with gpHasConsultation
    val gpHasConsultation = createGP(s"physician:gp:has-consultation$suffix", false, maarten.id)
    // 14. GP4 has no relationship to a patient
    // 
    // 15. Oncologist1 has recently treated Maarten
    oncologist1.treated ::= maarten
    oncologist1.treatedByTeam ::= maarten
    oncologist1.treatedInLastSixMonths ::= maarten
    // 16. OncologyNurse1 has no relationship to a patient 
    // 
    // 17. CardologyNurse1 has no relationship to a patient 
    // 
    // 18. CardologyNurse2 has no relationship to a patient 
    //
    // 19. Maarten is/was in the care unit of CardologyNurse3 
    cardiologyNurse3.patientsAdmittedInNurseUnit ::= maarten
    // 20. Wouter is/was in the care unit of CardologyNurse3 
    cardiologyNurse3.patientsAdmittedInNurseUnit ::= wouter
    // 21. Erna is/was in the care unit of CardologyNurse3 
    cardiologyNurse3.patientsAdmittedInNurseUnit ::= erna
    // 22. ElderCareNurse1 has no relationship to a patient
    //
    // 23. Maarten is admitted in the nurse unit of ElderCareNurse2
    // and she is responsible for Maarten
    elderCareNurse2.patientsAdmittedInNurseUnit ::= maarten
    elderCareNurse2.responsiblePatients ::= maarten

    /**
     * ********************************
     * THE REQUESTS
     * ********************************
     */
    requests((cardiologist1, "view", maartenStatus, List())) = Deny
    requests((cardiologist1, "view", bartStatus, List())) = Deny
    requests((cardiologist1, "view", wouterStatus, List())) = Permit
    requests((cardiologist2, "view", maartenStatus, List())) = Permit
    requests((cardiologist3, "view", maartenStatus, List())) = Permit
    requests((cardiologist4, "view", maartenStatus, List())) = Deny
    requests((cardiologistHead, "view", maartenStatus, List())) = Permit
    requests((cardiologistTriggered, "view", maartenStatus, List())) = Permit
    requests((emergencySpecialist1, "view", maartenStatus, List())) = Deny
    requests((emergencySpecialist1, "view", bartStatus, List())) = Permit
    requests((emergencySpecialist1, "view", wouterStatus, List())) = Permit
    requests((gp1, "view", maartenStatus, List())) = Permit
    requests((gp2, "view", maartenStatus, List())) = Permit
    requests((gp3, "view", maartenStatus, List())) = Permit
    requests((gp4, "view", maartenStatus, List())) = Deny
    requests((gpHasConsultation, "view", maartenStatus, List())) = Permit
    requests((oncologist1, "view", maartenStatus, List())) = Deny
    requests((cardiologyNurse1, "view", maartenStatus, List())) = Deny
    requests((cardiologyNurse2, "view", maartenStatus, List())) = Deny
    requests((cardiologyNurse3, "view", maartenStatus, List())) = Permit
    requests((cardiologyNurse3, "view", bartStatus, List())) = Deny
    requests((cardiologyNurse3, "view", ernaStatus, List())) = Deny
    requests((cardiologyNurse3, "view", wouterStatus, List())) = Permit
    requests((elderCareNurse1, "view", maartenStatus, List())) = Deny
    requests((elderCareNurse2, "view", maartenStatus, List())) = Permit
    requests((oncologyNurse, "view", maartenStatus, List())) = Deny
    requests((maarten, "view", maartenStatus, List())) = Permit
    requests((maarten, "view", wouterStatus, List())) = Deny
    requests((wouter, "view", wouterStatus, List())) = Deny
  }

  /**
   * ********************************
   * ENTITY STORE
   * ********************************
   */

  def createNondischargedPatient(id: String, isAllowedToAccessPMS: Boolean) = {
    val result = new Patient(id, isAllowedToAccessPMS)
    storeEntity(result)
    result
  }

  def createDischargedPatient(id: String,
    isAllowedToAccessPMS: Boolean, dischargedDate: LocalDateTime) = {
    val result = new Patient(id, isAllowedToAccessPMS, Some(dischargedDate))
    storeEntity(result)
    result
  }

  def createNurse(id: String, department: String,
    allowedToAccessPMS: Boolean, location: String, shiftStart: LocalDateTime,
    shiftStop: LocalDateTime) = {
    val result = new Nurse(id, department, allowedToAccessPMS, location,
      shiftStart, shiftStop, false)
    result.patientsAdmittedInNurseUnit ::= randomId()
    result.responsiblePatients ::= randomId()
    storeEntity(result)
    result
  }

  def createPatientStatus(id: String, owner: Patient,
    createdDate: LocalDateTime, patientStatus: String, indicatesEmergency: Boolean,
    operatorTriggeredEmergency: Boolean) = {
    val result = new PatientStatus(id, owner.id,
      owner.dischargedDate, createdDate, patientStatus,
      indicatesEmergency, operatorTriggeredEmergency)
    result.ownerResponsiblePhysicians ::= randomId()
    result.ownerWithdrawnConsents ::= randomId()
    storeEntity(result)
    result
  }

  def createCardiologist(id: String,
    isHeadPhysician: Boolean, triggeredBreakingGlass: Boolean,
    currentPatientInConsultation: String) = {
    val result = new Cardiologist(id, isHeadPhysician,
      triggeredBreakingGlass, Some(currentPatientInConsultation))
    result.admittedPatientsInCareUnit ::= randomId
    result.treated ::= randomId()
    result.treatedByTeam ::= randomId()
    result.treatedInLastSixMonths ::= randomId()
    result.primaryPatients ::= randomId()
    storeEntity(result)
    result
  }

  def createGP(id: String, triggeredBreakingGlass: Boolean,
    currentPatientInConsultation: String) = {
    val result = new GP(id, triggeredBreakingGlass,
      Some(currentPatientInConsultation))
    result.admittedPatientsInCareUnit ::= randomId
    result.treated ::= randomId()
    result.treatedByTeam ::= randomId()
    result.treatedInLastSixMonths ::= randomId()
    result.primaryPatients ::= randomId()
    storeEntity(result)
    result
  }

  def createEmergencySpecialist(id: String,
    isHeadPhysician: Boolean, triggeredBreakingGlass: Boolean,
    currentPatientInConsultation: String) = {
    val result = new EmergencySpecialist(id,
      isHeadPhysician, triggeredBreakingGlass,
      Some(currentPatientInConsultation))
    result.admittedPatientsInCareUnit ::= randomId
    result.treated ::= randomId()
    result.treatedByTeam ::= randomId()
    result.treatedInLastSixMonths ::= randomId()
    result.primaryPatients ::= randomId()
    storeEntity(result)
    result
  }

  def createElderCareSpecialist(id: String,
    isHeadPhysician: Boolean, triggeredBreakingGlass: Boolean,
    currentPatientInConsultation: String) = {
    val result = new ElderCareSpecialist(id,
      isHeadPhysician, triggeredBreakingGlass,
      Some(currentPatientInConsultation))
    result.admittedPatientsInCareUnit ::= randomId
    result.treated ::= randomId()
    result.treatedByTeam ::= randomId()
    result.treatedInLastSixMonths ::= randomId()
    result.primaryPatients ::= randomId()
    storeEntity(result)
    result
  }

  def createOncologist(id: String,
    isHeadPhysician: Boolean, triggeredBreakingGlass: Boolean,
    currentPatientInConsultation: String) = {
    val result = new Oncologist(id,
      isHeadPhysician, triggeredBreakingGlass,
      Some(currentPatientInConsultation))
    result.admittedPatientsInCareUnit ::= randomId
    result.treated ::= randomId()
    result.treatedByTeam ::= randomId()
    result.treatedInLastSixMonths ::= randomId()
    result.primaryPatients ::= randomId()
    storeEntity(result)
    result
  }

  /**
   * ********************************
   * PERSISTING TO THE DATABASE
   * ********************************
   */

  def persist(db: AttributeDatabaseConnection) {
    entities.values.foreach(_.persist(db))
  }

  /**
   * ********************************
   * HELPER: RANDOM IDS
   * ********************************
   */

  var counter = 0

  def randomId() = {
    counter += 1
    s"dkDe8dD4D0etp$counter"
  }

}
