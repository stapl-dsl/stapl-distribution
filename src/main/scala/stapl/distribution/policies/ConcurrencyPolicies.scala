package stapl.distribution.policies

import stapl.core._

object ConcurrencyPolicies extends BasicPolicy {
  
  import stapl.core.dsl._
  
  resource.owner = SimpleAttribute(String)
  // some attributes on which we will have contention
  resource.nbAccesses = SimpleAttribute(Number)
  subject.history = ListAttribute(String)
  // some attributes to fill up time
  subject.attribute1 = SimpleAttribute(String)
  subject.attribute2 = SimpleAttribute(String)
  subject.attribute3 = SimpleAttribute(String)
  subject.attribute4 = SimpleAttribute(String)
  subject.attribute5 = SimpleAttribute(String)
  subject.attribute6 = SimpleAttribute(String)
  subject.attribute7 = SimpleAttribute(String)
  subject.attribute8 = SimpleAttribute(String)
  subject.attribute9 = SimpleAttribute(String)
  subject.attribute10 = SimpleAttribute(String)
  
  val maxNbAccess = Policy("max-nb-accesses") := apply(DenyOverrides) to (
      Rule("check-attribute-1") := deny iff (subject.attribute1 == "somethingthatdoesnotexist"),
      Rule("check-attribute-2") := deny iff (subject.attribute2 == "somethingthatdoesnotexist"),
      Rule("check-attribute-3") := deny iff (subject.attribute3 == "somethingthatdoesnotexist"),
      Rule("check-attribute-4") := deny iff (subject.attribute4 == "somethingthatdoesnotexist"),
      Rule("check-attribute-5") := deny iff (subject.attribute5 == "somethingthatdoesnotexist"),
      Rule("check-attribute-6") := deny iff (subject.attribute6 == "somethingthatdoesnotexist"),
      Rule("check-attribute-7") := deny iff (subject.attribute7 == "somethingthatdoesnotexist"),
      Rule("check-attribute-8") := deny iff (subject.attribute8 == "somethingthatdoesnotexist"),
      Rule("check-attribute-9") := deny iff (subject.attribute9 == "somethingthatdoesnotexist"),
      Rule("check-attribute-10") := deny iff (subject.attribute10 == "somethingthatdoesnotexist"),
      Rule("deny") := deny iff (resource.nbAccesses gteq 5),
      Rule("permit") := permit performing (update(resource.nbAccesses, resource.nbAccesses + 1))
  ) 
  
  val chineseWall = Policy("chinese-wall") := apply(DenyOverrides) to (
      Rule("check-attribute-1") := deny iff (subject.attribute1 == "somethingthatdoesnotexist"),
      Rule("check-attribute-2") := deny iff (subject.attribute2 == "somethingthatdoesnotexist"),
      Rule("check-attribute-3") := deny iff (subject.attribute3 == "somethingthatdoesnotexist"),
      Rule("check-attribute-4") := deny iff (subject.attribute4 == "somethingthatdoesnotexist"),
      Rule("check-attribute-5") := deny iff (subject.attribute5 == "somethingthatdoesnotexist"),
      Rule("check-attribute-6") := deny iff (subject.attribute6 == "somethingthatdoesnotexist"),
      Rule("check-attribute-7") := deny iff (subject.attribute7 == "somethingthatdoesnotexist"),
      Rule("check-attribute-8") := deny iff (subject.attribute8 == "somethingthatdoesnotexist"),
      Rule("check-attribute-9") := deny iff (subject.attribute9 == "somethingthatdoesnotexist"),
      Rule("check-attribute-10") := deny iff (subject.attribute10 == "somethingthatdoesnotexist"),
      Rule("deny1") := deny iff ((resource.owner === "bank1") & ("bank2" in subject.history)),
      Rule("deny2") := deny iff ((resource.owner === "bank2") & ("bank1" in subject.history)),
      Rule("permit") := permit performing (append(subject.history, resource.owner))
  )

}