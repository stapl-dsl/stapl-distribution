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
import stapl.distribution.util.LatencyStatistics

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
class DatabaseAttributeFinderModule(val attributeDb: AttributeDatabaseConnection,
    enableStats: Boolean = false) extends AttributeFinderModule with Logging {
  
  val stats = new LatencyStatistics("Attribute database", 10000, 10, enableStats)
  
  /**
   * Fetch an attribute from the database. 
   * 
   * Important note: this database only supports SUBJECT and RESOURCE attributes.
   * 
   * @trows	RuntimeException	If an ENVIRONMENT or ACTION attribute is requested.
   */
  override def find(ctx: EvaluationCtx, cType: AttributeContainerType, name: String, 
      aType: AttributeType, multiValued: Boolean): Option[ConcreteValue] = {
    
    val entityId = cType match {
      case SUBJECT => ctx.subjectId
      case RESOURCE => ctx.resourceId
      case ACTION => return None // we don't support this
      case ENVIRONMENT => return None // we don't support this
    }
    
    //
    // First test the cache
    //
    val attribute = Attribute(cType, name, aType, multiValued)    
    // for the log messages
    val attributeName = s"$entityId.${attribute.toString()}"
    
    var toReturn: Option[ConcreteValue] = None
    
    aType match {
      case String => {
        val result = stats.time {
          attributeDb.getStringAttribute(entityId, cType, name)
        }
        if(! multiValued) {
          // convert list to single value
          if(result.length == 0) {
            error(s"No values found for $attributeName")
          } else if(result.length > 1) {
            error(s"Multiple values found for $attributeName")
          } else {
            toReturn = Some(result(0))
          }
        } else {
          toReturn = Some(result)
        }
      }
      case Number => {
        val result = stats.time {
          attributeDb.getLongAttribute(entityId, cType, name)
        }
        if(! multiValued) {
          // convert list to single value
          if(result.length == 0) {
            error(s"No values found for $attributeName")
          } else if(result.length > 1) {
            error(s"Multiple values found for $attributeName")
          } else {
            toReturn = Some(result(0))
          }
        } else {
          toReturn = Some(result)
        }
      }
      case Bool => {
        val result = stats.time {
          attributeDb.getBooleanAttribute(entityId, cType, name)
        }
        if(! multiValued) {
          // convert list to single value
          if(result.length == 0) {
            error(s"No values found for SimpleAttribute $attributeName")
          } else if(result.length > 1) {
            error(s"Multiple values found for SimpleAttribute $attributeName")
          } else {
            toReturn = Some(result(0))
          }
        } else {
          toReturn = Some(result)
        }
      }
      case DateTime =>  {
        val result = stats.time {
          attributeDb.getDateAttribute(entityId, cType, name)
        }
        if(! multiValued) {
          // convert list to single value
          if(result.length == 0) {
            error(s"No values found for SimpleAttribute $attributeName")
          } else if(result.length > 1) {
            error(s"Multiple values found for SimpleAttribute $attributeName")
          } else {
            toReturn = Some(result(0))
          }
        } else {
          toReturn = Some(result)
        }
      }
      case Day => throw new UnsupportedOperationException("Day attributes are not supported for now") // TODO implement
      case Time => throw new UnsupportedOperationException("Time attributes are not supported for now") // TODO implement
    }

    toReturn
  }

}