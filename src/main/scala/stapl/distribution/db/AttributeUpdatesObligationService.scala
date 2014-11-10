package stapl.distribution.db

import stapl.core.pdp.ObligationServiceModule
import stapl.core.ConcreteObligationAction
import stapl.core.ConcreteUpdateAttributeObligationAction
import stapl.core.String

class AttributeUpdatesObligationServiceModule(db: AttributeDatabaseConnection) extends ObligationServiceModule {
  
  override def fulfill(obl: ConcreteObligationAction) = {
    // we only support attribute updates
    obl match {
      case ConcreteUpdateAttributeObligationAction(entityId, attribute, value) =>
        db.storeAnyAttribute(entityId, attribute, value.representation)
        true
      case _ => false
    }
  }
}