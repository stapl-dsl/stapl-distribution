package stapl.distribution.db

import stapl.core.pdp.AttributeFinderModule
import stapl.core.pdp.EvaluationCtx
import stapl.core._
import stapl.core.ConcreteValue
import grizzled.slf4j.Logging

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
class DatabaseAttributeFinderModule(val attributeDb: AttributeDatabaseConnection) extends AttributeFinderModule with Logging {
  
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
        val result = attributeDb.getStringAttribute(entityId, cType, name)
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
        val result = attributeDb.getIntegerAttribute(entityId, cType, name)
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
        val result = attributeDb.getBooleanAttribute(entityId, cType, name)
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
        val result = attributeDb.getDateAttribute(entityId, cType, name)
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