package stapl.distribution.cache

import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import stapl.core.ConcreteValue
import com.hazelcast.core.IMap
import stapl.core.Attribute
import scala.collection.JavaConverters._
import stapl.core.ListAttribute
import stapl.core.String
import stapl.core.SimpleAttribute
import stapl.core.Number

object AttributeCacheTest {

  def main(args: Array[String]) {
    val subject = stapl.core.subject
    subject.roles = ListAttribute(String)
    subject.id = SimpleAttribute(Number)
    
    val attributeCache = new AttributeCache
    attributeCache.storeAttribute(1, "maarten", subject.roles, List("role1"))
    println("1: " + attributeCache.getCompleteCache)
    attributeCache.storeAttribute(1, "maarten", subject.id, 123)
    println("2: " + attributeCache.getCompleteCache)
    attributeCache.storeAttribute(1, "maarten", subject.roles, List("role2"))
    println("3: " + attributeCache.getCompleteCache) // role1 should NOT be overwritten
  }
}

/**
 * Class used for caching attributes in Hazelcast.
 */
class AttributeCache {

  val cfg = new Config();
  cfg.getNetworkConfig.getJoin.getMulticastConfig.setEnabled(false)
  cfg.getNetworkConfig.getJoin.getTcpIpConfig.setEnabled(true)
  cfg.getNetworkConfig.getJoin.getTcpIpConfig.addMember("127.0.0.1") // TODO this should be updated for distribution
  val hazelcast = Hazelcast.newHazelcastInstance(cfg)
  
  /**
   * The actual distributed map that we will be using as attribute cache.
   * The attributes are cached based on the evaluation id. For each evaluation
   * id, another map is stored. This map maps an attribute for a certain entity
   * to its value. Each attribute is saved in the map as the concatenation of the
   * entity id and the attribute name (result of toString()).
   */
  val attributeMap: IMap[Long, Map[String, ConcreteValue]] = hazelcast.getMap("attributes")

  /**
   * Returns the complete attribute cache. For testing purposes only,
   * DO NOT USE THIS FOR NORMAL OPERATIONS.
   */
  private[distribution] def getCompleteCache(): Map[Long, Map[String, ConcreteValue]] = {
    val keys = attributeMap.keySet().asScala
    var result = Map[Long, Map[String, ConcreteValue]]()
    for (key <- keys) {
      result += key -> attributeMap.get(key)
    }
    result
  }
  
  /**
   * Helper method to construct the key of a certain entityId and attribute combination.
   */
  private def toKey(entityId: String, attribute: Attribute): String = {
    s"$entityId.${attribute.toString()}"
  }

  /**
   * Tries to store the given attribute value in the attribute cache, but does
   * not overwrite the cached value should a value already be present for this
   * evaluation id and attribute. This method returns the value that is present
   * in the cache after this attempt. In case a cached value was already present,
   * that value is returned. Otherwise, the given value is returned.
   */
  def storeAttribute(evaluationId: Long, entityId: String, attribute: Attribute, value: ConcreteValue): ConcreteValue = {
    val attributeKey = toKey(entityId, attribute)
    val cachedAttributes = attributeMap.get(evaluationId)
    if (cachedAttributes == null) {
      // no value present in the cache for the given evaluation id
      // => create a new map with the given value
      val updatedCachedAttributes = Map[String, ConcreteValue](attributeKey -> value)
      val storedValue = attributeMap.putIfAbsent(evaluationId, updatedCachedAttributes)
      if (storedValue == null) {
        // we stored the given value in the cache
        value
      } else {
        // it seems someone beat us to it and other values for the given
        // evaluation id were stored in the mean while
        // => restart because this can contain a value for the given attribute as well!
        storeAttribute(evaluationId, entityId, attribute, value)
      }
    } else {
      // there are already values in the cache for the given evaluation id
      // => check if the cache already contains a value for the given attribute
      if (cachedAttributes.contains(attributeKey)) {
        // the cache already contains a value for the given evaluation id and attribute
        // => we do not overwrite this, just return this value
        cachedAttributes.get(attributeKey).get
      } else {
        // no value yet, so store the given value
        val updatedCachedAttributes = cachedAttributes + (attributeKey -> value)
        val storedValue = attributeMap.replace(evaluationId, updatedCachedAttributes)
        if (storedValue == null) {
          // we stored the given value in the cache
          value
        } else {
          // it seems someone beat us to it and other values for the given
          // evaluation id were stored in the mean while
          // => restart because this can contain a value for the given attribute as well!
          storeAttribute(evaluationId, entityId, attribute, value)
        }
      }
    }
  }

  /**
   * Returns the value of the given attribute for the given evaluation id
   * in the attribute cache. Returns None if the value was not present.
   */
  def getAttribute(evaluationId: Long, entityId: String, attribute: Attribute): Option[ConcreteValue] = {
    val cachedAttributes = attributeMap.get(evaluationId)
    if (cachedAttributes == null) {
      None
    } else {
      cachedAttributes.get(toKey(entityId, attribute))
    }
  }
  
  /**
   * Removes all cached values for the given evaluation id from the cache and
   * returns the cached values.
   * Returns an empty map if no values were present in the cache for the given
   * evaluation id.
   */
  def removeAttributes(evaluationId: Long): Map[String, ConcreteValue] = {
    val current = attributeMap.remove(evaluationId)
    if(current == null) {
      // instead of null, return an empty map
      Map[String, ConcreteValue]()
    } else {
      current
    }
  }
}