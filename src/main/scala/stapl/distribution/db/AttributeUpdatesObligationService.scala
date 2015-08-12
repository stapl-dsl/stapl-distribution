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

import stapl.core.pdp.ObligationServiceModule
import stapl.core.ConcreteObligationAction
import stapl.core.String
import stapl.core.ConcreteChangeAttributeObligationAction
import stapl.core.Update
import stapl.core.Append

class AttributeUpdatesObligationServiceModule(db: AttributeDatabaseConnection) extends ObligationServiceModule {
  
  override def fulfill(obl: ConcreteObligationAction) = {
    // we only support attribute updates
    obl match {
      case ConcreteChangeAttributeObligationAction(entityId, attribute, value, changeType) =>
        changeType match {
          case Update => db.updateAnyAttribute(entityId, attribute, value.representation)
          case Append => db.storeAnyAttribute(entityId, attribute, value.representation)
        }        
        true
      case _ => false
    }
  }
}