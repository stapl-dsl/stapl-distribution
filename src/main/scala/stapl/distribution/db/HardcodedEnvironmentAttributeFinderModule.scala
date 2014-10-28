package stapl.distribution.db

import stapl.core.pdp.AttributeFinderModule
import stapl.core.pdp.EvaluationCtx
import stapl.core._
import stapl.core.ConcreteValue
import grizzled.slf4j.Logging
import stapl.distribution.db.entities.DateHelper

/**
 * A class used for fetching attributes from a database during policy evaluation.
 * This attribute finder module fetches attributes from the given attribute database
 * and employs the given attribute cache.
 *
 * Constructor:
 * @param	attributeDb
 * 			An *opened* database connection. This class is not responsible for opening or closing
 *    		the database connections.
 */
class HardcodedEnvironmentAttributeFinderModule extends AttributeFinderModule with Logging {

  val dh = new DateHelper

  /**
   * Fetch an attribute from the database.
   *
   * Important note: this database only supports SUBJECT and RESOURCE attributes.
   *
   * @trows	RuntimeException	If an ENVIRONMENT or ACTION attribute is requested.
   */
  override def find(ctx: EvaluationCtx, cType: AttributeContainerType, name: String,
    aType: AttributeType, multiValued: Boolean): Option[ConcreteValue] = cType match {
    case SUBJECT => None // we don't support this
    case RESOURCE => None // we don't support this
    case ACTION => None // we don't support this
    case ENVIRONMENT => name match {
      case "currentDateTime" => Some(dh.now())
      case _ => None // we don't support this
    }
  }
}