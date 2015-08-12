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

import stapl.core._

object ConcurrencyPolicies extends BasicPolicy {
  
  import stapl.core.dsl._
  
  resource.owner = SimpleAttribute(String)
  // some attributes on which we will have contention
  resource.nbAccesses = SimpleAttribute(Number)
  subject.nbAccesses = SimpleAttribute(Number)
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
  
  val max1ResourceAccess = Policy("max-1-resource-access") := apply(DenyOverrides) to (
      Rule("deny") := deny iff (resource.nbAccesses gteq 1),
      Rule("permit") := permit performing (update(resource.nbAccesses, resource.nbAccesses + 1))
  ) 
  
  val max1SubjectAccess = Policy("max-1-subject-access") := apply(DenyOverrides) to (
      Rule("deny") := deny iff (subject.nbAccesses gteq 1),
      Rule("permit") := permit performing (update(subject.nbAccesses, subject.nbAccesses + 1))
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