package stapl.distribution.cache

import stapl.core.ConcreteValue
import stapl.core.Attribute
import stapl.core.ListAttribute
import stapl.core.String
import stapl.core.SimpleAttribute
import stapl.core.Number
import scala.collection.concurrent.TrieMap
import com.hazelcast.config.Config
import com.hazelcast.config.MapConfig
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.IMap
import scala.collection.JavaConverters._
import grizzled.slf4j.Logging

/**
 * Trait used for representing a concurrent attribute cache. A concurrent
 * attribute cache differs from a simple attribute cache in the fact that
 * you have to provide your evaluation id to get your attributes. As a result,
 * a concurrent attribute cache can be shared by multiple ConcurrentEvaluationContexts.
 */
trait ConcurrentAttributeCache {
  
  /**
   * Stores an attribute in the cache and returns the attribute that 
   * was actually stored in the cache (this can differ from implementation
   * to implementation, for example depending on whether a value was already
   * present in the cache or not). 
   */
  def store(evaluationId: Long, attribute: Attribute, value: ConcreteValue): ConcreteValue

  /**
   * Returns the value of the given attribute in the attribute cache. 
   * Returns None if no such value was present.
   */
  def get(evaluationId: Long, attribute: Attribute): Option[ConcreteValue]
  
  /**
   * Returns all attributes in the cache for a certain evaluation id.
   */
  def getAll(evaluationId: Long): Map[Attribute,ConcreteValue]

  /**
   * Removes all cached values from the cache.
   */
  def clear

  /**
   * Helper method to construct the key of a certain entityId and attribute combination.
   */
  protected def toKey(evaluationId: Long, attribute: Attribute): String = {
    s"$evaluationId:${attribute.toString()}"
  }
}

/**
 * Class used for concurrently caching attributes for multiple policy evaluations
 * on a single machine.
 */
class LocalConcurrentAttributeCache extends ConcurrentAttributeCache {

  /**
   * The actual map that we will be using as attribute cache.
   * 
   * Note that the map of attribute maps does not need to be a concurrent map
   * since this will only be updated at the start or the end of a policy evaluation. 
   */
  val attributeMap: scala.collection.concurrent.Map[(Long, Attribute), ConcreteValue] = new TrieMap

  /**
   * Tries to store the given attribute value in the attribute cache, but does
   * not overwrite the cached value should a value already be present for this
   * evaluation id and attribute. This method returns the value that is present
   * in the cache after this attempt. In case a cached value was already present,
   * that value is returned. Otherwise, the given value is returned.
   */
  override def store(evaluationId: Long, attribute: Attribute, value: ConcreteValue): ConcreteValue = {
    val key = (evaluationId, attribute)
    val result: Option[ConcreteValue] = attributeMap.putIfAbsent(key, value)
    result match {
      case Some(x) => {
        x // return the value that was already present
      }
      case None => value // return the given value (that is now in the cache)
    }
  }

  /**
   * Returns the value of the given attribute in the attribute cache. 
   * Returns None if no such value was present.
   */
  override def get(evaluationId: Long, attribute: Attribute): Option[ConcreteValue] = {
    attributeMap.get((evaluationId, attribute))   
  }
  
  /**
   * Returns all cached attributes for a certain evaluation id.
   * 
   * Expensive operation!
   */
  override def getAll(evaluationId: Long) = {
    val result: scala.collection.mutable.Map[Attribute, ConcreteValue] = scala.collection.mutable.Map()
    // TODO this can be implemented more efficiently...
    for(((e, attribute), value) <- attributeMap) {
      if(evaluationId == e) {
        result(attribute) = value
      }
    }   
    result.toMap
  }

  /**
   * Removes all cached values from the cache.
   */
  override def clear {
    attributeMap.clear
  }
}

/**
 * Class used for distributedly caching attributes in Hazelcast.
 * This cache can be shared by multiple policy evaluations.
 */
class DistributedAttributeCache(clusterMembers: String*) extends ConcurrentAttributeCache with Logging {

  private val cfg = new Config();
  cfg.getNetworkConfig.getJoin.getMulticastConfig.setEnabled(false)
  cfg.getNetworkConfig.getJoin.getTcpIpConfig.setEnabled(true)
  for (member <- clusterMembers) {
    cfg.getNetworkConfig.getJoin.getTcpIpConfig.addMember(member)
  }
  private val mapCfg = new MapConfig();
  mapCfg.setName("attribute-cache");
  mapCfg.setBackupCount(0);
  mapCfg.getMaxSizeConfig().setSize(0); // max_integer IMPORTANT: we assume that the cache will be big enough for all uses
  mapCfg.setTimeToLiveSeconds(0); // infinite TTL
  cfg.addMapConfig(mapCfg)
  private val hazelcast = Hazelcast.newHazelcastInstance(cfg)

  /**
   * The actual distributed map that we will be using as attribute cache.
   * The attributes are cached based on the evaluation id and the attribute within
   * this evaluation.
   */
  val attributeMap: IMap[(Long,Attribute), ConcreteValue] = hazelcast.getMap("attribute-cache")

  /**
   * Returns the complete attribute cache. For testing purposes only,
   * DO NOT USE THIS FOR NORMAL OPERATIONS.
   */
  private[distribution] def getCompleteCache(): Map[(Long,Attribute), ConcreteValue] = {
    val keys = attributeMap.keySet().asScala
    var result = Map[(Long,Attribute), ConcreteValue]()
    for (key <- keys) {
      result += key -> attributeMap.get(key)
    }
    result
  }

  /**
   * Tries to store the given attribute value in the attribute cache, but does
   * not overwrite the cached value should a value already be present for this
   * evaluation id and attribute. This method returns the value that is present
   * in the cache after this attempt. In case a cached value was already present,
   * that value is returned. Otherwise, the given value is returned.
   */
  override def store(evaluationId: Long, attribute: Attribute, value: ConcreteValue): ConcreteValue = {
    val key = (evaluationId, attribute)
    val result: Option[ConcreteValue] = Option(attributeMap.putIfAbsent(key, value)) // wrap in option from Java to Scala
    result match {
      case None => value // there was no mapping present for this key => the given value was stored => return the given value
      case Some(v) => v // there was an attribute present for this key => this attribute is still in the cache => return this value
    }
  }

  /**
   * Returns the value of the given attribute for the given evaluation id
   * in the attribute cache. Returns None if the value was not present.
   */
  override def get(evaluationId: Long, attribute: Attribute): Option[ConcreteValue] = {
    Option(attributeMap.get(toKey(evaluationId, attribute)))
  }
  
  /**
   * Returns an empty list since getting all cached attributes for a certain evaluation
   * id is too expensive...
   */
  override def getAll(evaluationId: Long) = {
    warn("getAll() called on DistributedAttributeCache, which returns an empty map")
    Map()
  }
  
  /**
   * Removes all contents of the attribute cache.
   */
  override def clear {
    attributeMap.clear
  }
}