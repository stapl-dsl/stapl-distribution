package stapl.distribution.db.entities

import org.joda.time.LocalDateTime
import stapl.distribution.db.AttributeDatabaseConnection
import stapl.examples.policies.EhealthPolicy

abstract class Entity(var id: String) {  
  def persist(db: AttributeDatabaseConnection) // the id does not have to be stored
}

abstract class Person(_id: String,
  var roles: List[String] = List.empty) extends Entity(_id) {

  override def persist(db: AttributeDatabaseConnection) = {
    db.storeAttribute(id, EhealthPolicy.subject.roles, roles)
  }
}

class Patient(_id: String,
  var isAllowedToAccessPMS: Boolean,
  var dischargedDate: Option[LocalDateTime] = None)
  extends Person(_id) {
  roles ::= "patient"

  override def persist(db: AttributeDatabaseConnection) = {
    super.persist(db)
    db.storeAttribute(id, EhealthPolicy.subject.allowed_to_access_pms, isAllowedToAccessPMS)
  }
}

abstract class MedicalPersonnel(_id: String,
  var department: String,
  var triggeredBreakingGlass: Boolean) extends Person(_id) {
  roles ::= "medical_personnel"

  override def persist(db: AttributeDatabaseConnection) = {
    super.persist(db)
    db.storeAttribute(id, EhealthPolicy.subject.department, department)
    db.storeAttribute(id, EhealthPolicy.subject.triggered_breaking_glass, triggeredBreakingGlass)
  }
}

abstract class Physician(_id: String,
  _department: String,
  _triggeredBreakingGlass: Boolean,
  var isHeadPhysician: Boolean,
  var currentPatientInConsultation: Option[String] = None,
  _roles: List[String] = List.empty,
  var treatedInLastSixMonths: List[String] = List.empty,
  var primaryPatients: List[String] = List.empty,
  var treatedByTeam: List[String] = List.empty,
  var treated: List[String] = List.empty,
  var admittedPatientsInCareUnit: List[String] = List.empty) extends MedicalPersonnel(_id, _department, _triggeredBreakingGlass) {
  roles ::= "physician"

  override def persist(db: AttributeDatabaseConnection) = {
    super.persist(db)
    db.storeAttribute(id, EhealthPolicy.subject.is_head_physician, isHeadPhysician)
    currentPatientInConsultation.foreach(x => db.storeAttribute(id, EhealthPolicy.subject.current_patient_in_consultation, x))
    db.storeAttribute(id, EhealthPolicy.subject.treated, treated)
    db.storeAttribute(id, EhealthPolicy.subject.treated_by_team, treatedByTeam)
    db.storeAttribute(id, EhealthPolicy.subject.treated_in_last_six_months, treatedInLastSixMonths)
    db.storeAttribute(id, EhealthPolicy.subject.admitted_patients_in_care_unit, admittedPatientsInCareUnit)
    db.storeAttribute(id, EhealthPolicy.subject.primary_patients, primaryPatients)
  }
}

class GP(_id: String,
  _triggeredBreakingGlass: Boolean,
  _currentPatientInConsultation: Option[String] = None) extends Physician(_id, "doesnotmatter", false, _triggeredBreakingGlass, _currentPatientInConsultation) {
	roles ::= "gp"
}

class Cardiologist(_id: String,
  _triggeredBreakingGlass: Boolean,
  _isHeadPhysician: Boolean,
  _currentPatientInConsultation: Option[String] = None) extends Physician(_id, "cardiology", _isHeadPhysician, _triggeredBreakingGlass, _currentPatientInConsultation)

class Oncologist(_id: String,
  _triggeredBreakingGlass: Boolean,
  _isHeadPhysician: Boolean,
  _currentPatientInConsultation: Option[String] = None) extends Physician(_id, "oncology", _isHeadPhysician, _triggeredBreakingGlass, _currentPatientInConsultation)

class ElderCareSpecialist(_id: String,
  _triggeredBreakingGlass: Boolean,
  _isHeadPhysician: Boolean,
  _currentPatientInConsultation: Option[String] = None) extends Physician(_id, "elder_care", _isHeadPhysician, _triggeredBreakingGlass, _currentPatientInConsultation)

class EmergencySpecialist(_id: String,
  _triggeredBreakingGlass: Boolean,
  _isHeadPhysician: Boolean,
  _currentPatientInConsultation: Option[String] = None) extends Physician(_id, "emergency", _isHeadPhysician, _triggeredBreakingGlass, _currentPatientInConsultation)

class Nurse(_id: String,
  _department: String,
  var allowedToAccessPMS: Boolean,
  var location: String,
  var shiftStart: LocalDateTime,
  var shiftStop: LocalDateTime,
  _triggeredBreakingGlass: Boolean,
  var patientsAdmittedInNurseUnit: List[String] = List.empty,
  var responsiblePatients: List[String] = List.empty) extends MedicalPersonnel(_id, _department, _triggeredBreakingGlass) {
  roles ::= "nurse"
    
  override def persist(db: AttributeDatabaseConnection) {
    super.persist(db)
    db.storeAttribute(id, EhealthPolicy.subject.allowed_to_access_pms, allowedToAccessPMS)
    db.storeAttribute(id, EhealthPolicy.subject.location, location)
    db.storeAttribute(id, EhealthPolicy.subject.shift_start, shiftStart)
    db.storeAttribute(id, EhealthPolicy.subject.shift_stop, shiftStop)
    db.storeAttribute(id, EhealthPolicy.subject.admitted_patients_in_nurse_unit, patientsAdmittedInNurseUnit)
    db.storeAttribute(id, EhealthPolicy.subject.responsible_patients, responsiblePatients)
  }    
}

abstract class Resource(id: String, val _type: String) extends Entity(id) {
    
  override def persist(db: AttributeDatabaseConnection) {
    db.storeAttribute(id, EhealthPolicy.resource.type_, _type)
  }   
}

class PatientStatus(_id: String, var ownerId: String, var ownerDischargedDate: Option[LocalDateTime],
  var createdDate: LocalDateTime, var patientStatus: String, var indicatesEmergency: Boolean,
  var operatorTriggeredEmergency: Boolean, var ownerResponsiblePhysicians: List[String] = List.empty,
  var ownerWithdrawnConsents: List[String] = List.empty) extends Resource(_id, "patientstatus") {
    
  override def persist(db: AttributeDatabaseConnection) {
    super.persist(db)
    db.storeAttribute(id, EhealthPolicy.resource.owner_id, ownerId)
    db.storeAttribute(id, EhealthPolicy.resource.indicates_emergency, indicatesEmergency)
    db.storeAttribute(id, EhealthPolicy.resource.operator_triggered_emergency, operatorTriggeredEmergency)
    db.storeAttribute(id, EhealthPolicy.resource.owner_withdrawn_consents, ownerWithdrawnConsents)
    db.storeAttribute(id, EhealthPolicy.resource.owner_responsible_physicians, ownerResponsiblePhysicians)
    db.storeAttribute(id, EhealthPolicy.resource.owner_discharged, ownerDischargedDate.isDefined)
    ownerDischargedDate.foreach(x => db.storeAttribute(id, EhealthPolicy.resource.owner_discharged_dateTime, x))
    db.storeAttribute(id, EhealthPolicy.resource.patient_status, patientStatus)
    db.storeAttribute(id, EhealthPolicy.resource.created, createdDate)
  }   
}