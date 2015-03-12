package stapl.distribution.components

import akka.actor.ActorRef
import akka.actor.ActorLogging
import akka.actor.Actor
import akka.actor.Terminated
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import akka.actor.Props
import scala.collection.mutable.{ Map, Queue }
import stapl.distribution.components.CoordinatorForemanProtocol.PolicyEvaluationResult
import stapl.distribution.db.AttributeDatabaseConnection
import stapl.core.ConcreteChangeAttributeObligationAction
import stapl.core.Attribute
import stapl.core.Update
import stapl.core.Append
import stapl.distribution.util.LatencyStatistics
import scala.collection.mutable.HashMap
import stapl.core.ConcreteValue
import grizzled.slf4j.Logging
import stapl.core.ConcreteChangeAttributeObligationAction
import stapl.core.ConcreteChangeAttributeObligationAction
import scala.collection.mutable.ListBuffer

/**
 * Actor used for processing attribute updates asynchronously from the
 * coordinator. Every message sent to this UpdateWorker is handled sequentially
 * and blocking, so be sure to assign UpdateWorkers to separate threads.
 */
class UpdateWorker(coordinator: ActorRef, db: AttributeDatabaseConnection) extends Actor with ActorLogging {

  log.debug(s"Start constructor of update worker $self")

  val stats = new LatencyStatistics(s"UpdateWorker $self", 50, 10)

  /**
   * Note: we use the ObligationActions as messages.
   */
  def receive = {

    /**
     * Update an attribute value.
     */
    case update: ConcreteChangeAttributeObligationAction =>
      log.debug(s"Starting attribute update: $update")
      val ConcreteChangeAttributeObligationAction(entityId, attribute, value, changeType) = update
      stats.time {
        changeType match {
          case Update => db.updateAnyAttribute(entityId, attribute, value.representation)
          case Append => db.storeAnyAttribute(entityId, attribute, value.representation)
        }
      }
      coordinator ! UpdateFinished(self)
      log.debug(s"Finished attribute update: $update")

    case "terminate" => context.stop(self)

    case x => log.warning(s"Unknown message receiced: $x")
  }

  log.debug(s"End constructor of update worker $self")

}

/**
 * For communication between the UpdateWorkers and the Coordinator.
 */
case class UpdateFinished(updateWorker: ActorRef)

/**
 * Abstract class used for caching or storing attribute updates.
 */
abstract class UpdatedAttributeStore extends Logging {

  /**
   * Stores an attribute update.
   */
  def store(update: ConcreteChangeAttributeObligationAction)

  /**
   * Notify this UpdatedAttributeStore that an ongoing update has finished.
   * The result of this information depends on the subclass.
   */
  def updateFinished(finishedUpdate: ConcreteChangeAttributeObligationAction)

  /**
   * Adds the appropriate attributes in this store to the given request.
   */
  def addSuitableAttributes(request: PolicyEvaluationRequest): PolicyEvaluationRequest
}

/**
 * An UpdatedAttributeStore that stores only the ongoing attribute updates.
 */
class OngoingAttributeUpdatesStore extends UpdatedAttributeStore {

  /**
   * The ongoing updates per entityId.
   */
  private val subjectId2OngoingUpdates = new HashMap[String, Map[Attribute, ConcreteValue]]
  private val resourceId2OngoingUpdates = new HashMap[String, Map[Attribute, ConcreteValue]]

  /**
   * Stores an attribute update.
   */
  override def store(update: ConcreteChangeAttributeObligationAction) {
    val target = update.attribute.cType match {
      case stapl.core.SUBJECT => subjectId2OngoingUpdates
      case stapl.core.RESOURCE => resourceId2OngoingUpdates
      case x => throw new IllegalArgumentException(s"You can only update SUBJECT or RESOURCE attributes. Given container type: $x")
    }
    if (target.contains(update.entityId)) {
      target(update.entityId)(update.attribute) = update.value
    } else {
      target(update.entityId) = Map(update.attribute -> update.value)
    }
  }

  /**
   * Notify this OngoingAttributeUpdatesStore that an ongoing update has finished.
   * This OngoingAttributeUpdatesStore will remove the update from the store.
   */
  override def updateFinished(finishedUpdate: ConcreteChangeAttributeObligationAction) {
    // remove the ongoing update from the list of ongoing updates    
    // per attribute IF this has not been overwritten in the meanwhile
    val target = finishedUpdate.attribute.cType match {
      case stapl.core.SUBJECT => subjectId2OngoingUpdates
      case stapl.core.RESOURCE => resourceId2OngoingUpdates
      // no need to check the other cases, this has been checked when adding
      // to ongoingUpdates in store(...)
    }
    if (target.contains(finishedUpdate.entityId)) {
      val values = target(finishedUpdate.entityId)
      if (values.contains(finishedUpdate.attribute) && values(finishedUpdate.attribute) == finishedUpdate.value) {
        values.remove(finishedUpdate.attribute)
      }
      if (values.isEmpty) {
        target.remove(finishedUpdate.entityId)
      }
    }
  }

  /**
   * Adds the appropriate attributes in this store to the given request.
   */
  override def addSuitableAttributes(request: PolicyEvaluationRequest): PolicyEvaluationRequest = {
    var attributes = request.extraAttributes
    // Note: subjectId2OngoingUpdates will only contain this request if
    // we should also manage this request => this method can be used from
    // both the subject-specific and resource-specific methods.
    // Note: there will never be tentative updates for resource attributes
    if (subjectId2OngoingUpdates.contains(request.subjectId)) {
      val toAdd = subjectId2OngoingUpdates(request.subjectId).toSeq
      attributes ++= toAdd
      debug(s"[Evaluation ${request.id}] Added the following ongoing attributes because of ongoing updates on the SUBJECT: $toAdd")
    }
    if (resourceId2OngoingUpdates.contains(request.resourceId)) {
      val toAdd = resourceId2OngoingUpdates(request.resourceId).toSeq
      attributes ++= toAdd
      debug(s"[Evaluation ${request.id}] Added the following ongoing attributes because of ongoing updates on the RESOURCE: $toAdd")
    }
    request.copy(extraAttributes = attributes)
  }
}

/**
 * An UpdatedAttributeStore that caches updated attributes in a LRU cache
 * of fixed maximal cache size. LRU is calculated based on the update only,
 * not the last time an attribute has been added to a request.
 *
 * This class is useful to store updated attributes while the database has not
 * been updated yet or has not reached a consistent state yet.
 *
 * NOTICE that the cache size should be sufficiently large to cover this
 * inconsistency window and that the size of this window depends on the number
 * of updates in the policy and the exact requests sent to this policy.
 * Conclusion: not easy to estimate correctly, so make it large :)
 *
 * ALSO NOTICE that if the cache size is large, there is also a larger inconsistency
 * window with out-of-band attribute updates. We currently do not solve this issue,
 * but possible solutions are:
 * 1. 	assume that there will be no out-of-band attribute updates for the
 * 		attributes in obligations
 * 2.	impose a time to live in order to discard stale updates after a time
 * 3.	introduce invalidation functionality to notify the cache of attributes
 * 		that have been updated out-of-band
 * => TODO
 *
 * ALSO NOTICE THAT WE ONLY STORE THE MOST RECENT VALUE IN CASE OF CONSECUTIVE
 * ATTRIBUTE UPDATES of the same attribute of the same entity.  
 */
class UpdatedAttributeCache(size: Int = 1000) extends UpdatedAttributeStore {

  /**
   * The shifting queue of updated attributes.
   */
  private val updatedAttributes = new ListBuffer[(String,Attribute)]()

  /**
   * The updated attributes per entity (for fast access).
   */
  private val subjectId2UpdatedAttributes = new HashMap[String, Map[Attribute, ConcreteValue]]
  private val resourceId2UpdatedAttributes = new HashMap[String, Map[Attribute, ConcreteValue]]

  /**
   * Stores an attribute update.
   */
  override def store(update: ConcreteChangeAttributeObligationAction) {
    // if another update for this entity and this attribute is already present
    // in the queue, we have to remove the previous value from the queue and
    // add it to the end of the queue again (i.e., as the recent update)
    val target = update.attribute.cType match {
      case stapl.core.SUBJECT => subjectId2UpdatedAttributes
      case stapl.core.RESOURCE => resourceId2UpdatedAttributes
      case x => throw new IllegalArgumentException(s"You can only update SUBJECT or RESOURCE attributes. Given container type: $x")
    }
    if (target.contains(update.entityId)) {
      // we already have a value for this entity and this attribute
      // => update the value in the map...
      target(update.entityId)(update.attribute) = update.value
      // ... and move the update to the back of the queue
      val queueKey = (update.entityId,update.attribute)
      updatedAttributes -= queueKey
      updatedAttributes += queueKey
      // note: we do not have to check the size of the queue here because
      // we replaced an item
    } else {
      // we do not have a value for this entity and this attribute yet
      // => add the value to the map...
      target(update.entityId) = Map(update.attribute -> update.value)
      // ... and just add the queue key to the end of the queue ...
      val queueKey = (update.entityId,update.attribute)
      updatedAttributes += queueKey      
      // ... and check the size of the queue
      if(updatedAttributes.size > size) {
        // remove the head
        updatedAttributes.remove(0)
      }
    }
  }

  /**
   * Notify this UpdatedAttributeCache that an ongoing update has finished.
   * This has no effect for an UpdatedAttributeCache.
   */
  override def updateFinished(finishedUpdate: ConcreteChangeAttributeObligationAction) {
    // don't do anything
  }

  /**
   * Adds the appropriate attributes in this store to the given request.
   */
  override def addSuitableAttributes(request: PolicyEvaluationRequest): PolicyEvaluationRequest = {
    var attributes = request.extraAttributes
    if (subjectId2UpdatedAttributes.contains(request.subjectId)) {
      val toAdd = subjectId2UpdatedAttributes(request.subjectId).toSeq
      attributes ++= toAdd
      debug(s"[Evaluation ${request.id}] Added the following cached updated attributes because of ongoing updates on the SUBJECT: $toAdd")
    }
    if (resourceId2UpdatedAttributes.contains(request.resourceId)) {
      val toAdd = resourceId2UpdatedAttributes(request.resourceId).toSeq
      attributes ++= toAdd
      debug(s"[Evaluation ${request.id}] Added the following cached updated attributes because of ongoing updates on the RESOURCE: $toAdd")
    }
    request.copy(extraAttributes = attributes)
  }
}