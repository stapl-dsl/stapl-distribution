package stapl.distribution.db

import stapl.core.pdp.AttributeFinderModule
import stapl.core.pdp.EvaluationCtx
import stapl.core._
import stapl.core.ConcreteValue
import grizzled.slf4j.Logging
import stapl.distribution.cache.AttributeCache

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
class DatabaseAttributeFinderModule(val attributeDb: AttributeDatabaseConnection, val attributeCache: AttributeCache) extends AttributeFinderModule with Logging {
  
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
      case _ => throw new RuntimeException("Only SUBJECT and RESOURCE attributes are supported. Given attribute container type: " + cType.toString())
    }
    
    //
    // First test the cache
    //
    val attribute = Attribute(cType, name, aType, multiValued)    
    // for the log messages
    val attributeName = s"$entityId.${attribute.toString()}"
    val cachedValue = attributeCache.getAttribute(ctx.evaluationId, entityId, attribute)
    cachedValue match {
      case Some(value) => {
        info(s"Found value for $attributeName in the cache: $value")
        value
      }
      case None => // nothing to do, just continue
    }
    
    //
    // Not in the cache => use the database
    //
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
    
    // Finally, store the found value in the cache IF we found at least one value
    toReturn match {
      case None => warn(s"No values found for $attributeName")
      case Some(value) => {
        attributeCache.storeAttribute(ctx.evaluationId, entityId, attribute, value)
    	info(s"Found value for attribute $attributeName and stored in attribute cache: $value")
      }
    }
    toReturn
  }

}