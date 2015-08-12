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