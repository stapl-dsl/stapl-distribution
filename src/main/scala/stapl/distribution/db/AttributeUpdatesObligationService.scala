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