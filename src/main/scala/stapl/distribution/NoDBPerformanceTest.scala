package stapl.distribution

import stapl.examples.policies.EhealthPolicy
import stapl.core.pdp.PDP
import stapl.core.pdp.AttributeFinder
import stapl.core.pdp.RequestCtx
import stapl.core.Result
import stapl.core.NotApplicable
import stapl.core.Deny
import stapl.core.Permit
import stapl.core.dsl.log
import stapl.core.ConcreteValue
import stapl.core.Attribute
import stapl.distribution.util.Timer
import org.joda.time.LocalDateTime

object NoDBPerformanceTest extends App {

  import EhealthPolicy._
  // set up the PDP, use an empty attribute finder since we will provide all attributes in the request
  val pdp = new PDP(naturalPolicy, new AttributeFinder)

  val subjectId = "maarten"
  val actionId = "view"
  val resourceId = "doc123"
  val extraAttributes = List[(Attribute, ConcreteValue)](
    subject.roles -> List("medical_personnel", "nurse"),
    subject.triggered_breaking_glass -> false,
    subject.department -> "elder_care",
    subject.allowed_to_access_pms -> true,
    subject.shift_start -> new LocalDateTime(2014, 6, 24, 9, 0, 0),
    subject.shift_stop -> new LocalDateTime(2014, 6, 24, 17, 0, 0),
    subject.location -> "hospital",
    subject.admitted_patients_in_nurse_unit -> List("patientX", "patientY"),
    subject.responsible_patients -> List("patientX", "patientZ"),
    resource.owner_id -> "patientX",
    resource.owner_withdrawn_consents -> List("subject1"),
    resource.type_ -> "patientstatus",
    resource.created -> new LocalDateTime(2014, 6, 22, 14, 2, 1), // three days ago
    environment.currentDateTime -> new LocalDateTime(2014, 6, 24, 14, 2, 1))

  val nbRuns = 1000000
  val timer = new Timer()
  for (i <- 0 to nbRuns) {
    timer time {
      pdp.evaluate(subjectId, actionId, resourceId, extraAttributes: _*)
    }
  }
  println(s"Mean after $nbRuns runs = ${timer.mean} ms")
}