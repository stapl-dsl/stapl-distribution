package stapl.distribution.cache

import stapl.core.pdp.RequestCtx
import stapl.core.pdp.RemoteEvaluator
import stapl.core.pdp.AttributeFinder
import stapl.core.Attribute
import stapl.core.ConcreteValue
import stapl.core.AttributeNotFoundException
import stapl.core.pdp.EvaluationCtx
import grizzled.slf4j.Logging

/**
 * An evaluation context that applies a concurrent attribute cache.
 */
class ConcurrentEvaluationContext(
    override val evaluationId: Long, 
    val request: RequestCtx, 
    val finder: AttributeFinder, 
    override val remoteEvaluator: RemoteEvaluator, 
    val concurrentAttributeCache: ConcurrentAttributeCache) 
    extends EvaluationCtx with Logging {
  
  override val subjectId: String = request.subjectId
  
  override val resourceId: String = request.resourceId
  
  override val actionId: String = request.actionId
  
  override def cachedAttributes = concurrentAttributeCache.getAll(evaluationId)
                            
  /**
   * Try to find the value of the given attribute. If the value is already
   * in the attribute cache, that value is returned. Otherwise, the attribute
   * finder is checked and the found value is stored in the attribute cache if
   * a value is found.
   */
  override def findAttribute(attribute: Attribute): ConcreteValue = {
    concurrentAttributeCache.get(evaluationId, attribute) match {
      case Some(value) => {
        debug("FLOW: found value of " + attribute + " in cache: " + value)
        value
      }
      case None => { // Not in the cache
        try{
          val value: ConcreteValue = finder.find(this, attribute)
          concurrentAttributeCache.store(evaluationId, attribute, value)
          debug("FLOW: retrieved value of " + attribute + ": " + value + " and added to cache")
          value
        } catch {
          case e: AttributeNotFoundException => 
            debug(s"Didn't find value of $attribute anywhere, exception thrown")
            throw e
          case e: Exception =>
            debug(s"Unknown exception thrown: $e")
            throw e
        }
      }
    }
  }

}