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
import scala.collection.mutable.ListBuffer
import java.util.NoSuchElementException

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

  sealed abstract class UpdateStatus
  case object Normal extends UpdateStatus
  case object Tentative extends UpdateStatus

  /**
   * The map of evaluation id -> tentative attribute updates that were present
   * in the result of that evaluation.
   */
  protected val evaluationId2TentativeAttributeUpdates = Map[String, ListBuffer[ConcreteChangeAttributeObligationAction]]()

  /**
   * The map of tentative attribute update -> the ids of the evaluations that
   * have been added these tentative attribute updates.
   */
  protected val tentativeAttributeUpdate2EvaluationIds = Map[(String, Attribute), ListBuffer[String]]()

  /**
   * Stores an attribute update.
   */
  protected def store(update: ConcreteChangeAttributeObligationAction, status: UpdateStatus): Unit
  def store(update: ConcreteChangeAttributeObligationAction): Unit = store(update, Normal)

  /**
   * Notify this UpdatedAttributeStore that an ongoing update has finished.
   * The result of this information depends on the subclass.
   */
  def updateFinished(finishedUpdate: ConcreteChangeAttributeObligationAction)

  /**
   * Stores a tentative attribute update. The store will keep track of which
   * policy evaluations have used this tentative update for possible rollbacks
   * later on.
   */
  def storeTentative(evaluationId: String, update: ConcreteChangeAttributeObligationAction) {
    // store in our general administration
    store(update, Tentative)
    // update the administration for tentative attribute updates
    val key = (update.entityId, update.attribute)
    if (evaluationId2TentativeAttributeUpdates.contains(evaluationId)) {
      evaluationId2TentativeAttributeUpdates(evaluationId) += update
    } else {
      // first time that we see an update for this evaluation => initialize the administration
      evaluationId2TentativeAttributeUpdates(evaluationId) = ListBuffer(update)
      tentativeAttributeUpdate2EvaluationIds(key) = ListBuffer()
    }
    debug(s"[Evaluation $evaluationId] Stored tentative attribute update: $update")
  }

  private def cleanupTentative(evaluationId: String) {
    for (update <- evaluationId2TentativeAttributeUpdates(evaluationId)) {
      val key = (update.entityId, update.attribute)
      tentativeAttributeUpdate2EvaluationIds.remove(key)
    }
    evaluationId2TentativeAttributeUpdates.remove(evaluationId)
  }

  /**
   * Aborts the tentative attribute updates of the policy evaluation with given
   * id by removing the updates from the administration and returning the ids of
   * policy evaluations that have been added these tentatively updated attributes.
   */
  def abortTentativeUpdates(evaluationId: String): Seq[String] = {
    // build up the evaluations that should be restarted
    val evaluationsThatShouldBeRestarted = ListBuffer[String]()
    if (evaluationId2TentativeAttributeUpdates.contains(evaluationId)) {
      for (update <- evaluationId2TentativeAttributeUpdates(evaluationId)) {
        val key = (update.entityId, update.attribute)
        evaluationsThatShouldBeRestarted ++= tentativeAttributeUpdate2EvaluationIds(key)
      }
      // clean up the administration
      cleanupTentative(evaluationId)
      debug(s"[Evaluation $evaluationId] Aborted tentative attribute updates")
    } else {
      debug(s"[Evaluation $evaluationId] Aborted tentative attribute updates (there were none)")
    }
    // return
    evaluationsThatShouldBeRestarted
  }

  /**
   * Finalizes the tentative attribute updates of the policy evaluation with given
   * id by removing them from the administration and returning them, so that they can actually
   * be processed in the database.
   */
  def finalizeTentativeUpdates(evaluationId: String): Seq[ConcreteChangeAttributeObligationAction] = {
    if (evaluationId2TentativeAttributeUpdates.contains(evaluationId)) {
      val toReturn = evaluationId2TentativeAttributeUpdates(evaluationId)
      cleanupTentative(evaluationId)
      debug(s"[Evaluation $evaluationId] Finalized tentative attribute updates")
      toReturn
    } else {
      debug(s"[Evaluation $evaluationId] Finalized tentative attribute updates (there were none)")
      List[ConcreteChangeAttributeObligationAction]()
    }
  }

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
  private val subjectId2OngoingUpdates = new HashMap[String, Map[Attribute, (ConcreteValue, UpdateStatus)]]
  private val resourceId2OngoingUpdates = new HashMap[String, Map[Attribute, (ConcreteValue, UpdateStatus)]]

  /**
   * Stores an attribute update.
   */
  override def store(update: ConcreteChangeAttributeObligationAction, status: UpdateStatus) {
    val target = update.attribute.cType match {
      case stapl.core.SUBJECT => subjectId2OngoingUpdates
      case stapl.core.RESOURCE => resourceId2OngoingUpdates
      case x => throw new IllegalArgumentException(s"You can only update SUBJECT or RESOURCE attributes. Given container type: $x")
    }
    if (target.contains(update.entityId)) {
      target(update.entityId)(update.attribute) = (update.value, status)
    } else {
      target(update.entityId) = Map(update.attribute -> (update.value, status))
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
      if (values.contains(finishedUpdate.attribute) && values(finishedUpdate.attribute)._1 == finishedUpdate.value) {
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
    val attributes = ListBuffer(request.extraAttributes: _*)
    // Note: subjectId2OngoingUpdates will only contain this request if
    // we should also manage this request => this method can be used from
    // both the subject-specific and resource-specific methods.
    // Note: there will never be tentative updates for resource attributes
    if (subjectId2OngoingUpdates.contains(request.subjectId)) {
      val toAdd = subjectId2OngoingUpdates(request.subjectId).toSeq
      for ((attribute, (value, status)) <- toAdd) {
        attributes += ((attribute, value))
        status match {
          case Normal =>
            debug(s"[Evaluation ${request.id}] Added the following ongoing attribute because of ongoing updates on the SUBJECT: ($attribute,$value)")
          case Tentative =>
            tentativeAttributeUpdate2EvaluationIds((request.subjectId, attribute)) += request.id
            debug(s"[Evaluation ${request.id}] Added the following TENTATIVELY ongoing attribute because of ongoing updates on the SUBJECT: ($attribute,$value)")
        }
      }
    }
    if (resourceId2OngoingUpdates.contains(request.resourceId)) {
      val toAdd = resourceId2OngoingUpdates(request.resourceId).toSeq
      for ((attribute, (value, status)) <- toAdd) {
        attributes += ((attribute, value))
        status match {
          case Normal =>
            debug(s"[Evaluation ${request.id}] Added the following ongoing attribute because of ongoing updates on the RESOURCE: ($attribute,$value)")
          case Tentative =>
            tentativeAttributeUpdate2EvaluationIds((request.resourceId, attribute)) += request.id
            debug(s"[Evaluation ${request.id}] Added the following TENTATIVELY ongoing attribute because of ongoing updates on the RESOURCE: ($attribute,$value)")
        }
      }
    }
    request.copy(extraAttributes = List(attributes: _*))
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
  private val updatedAttributes = new ListBuffer[(String, Attribute)]()

  /**
   * The updated attributes per entity (for fast access).
   */
  private val subjectId2UpdatedAttributes = new HashMap[String, Map[Attribute, (ConcreteValue, UpdateStatus)]]
  private val resourceId2UpdatedAttributes = new HashMap[String, Map[Attribute, (ConcreteValue, UpdateStatus)]]

  /**
   * Stores an attribute update.
   */
  override def store(update: ConcreteChangeAttributeObligationAction, status: UpdateStatus) {
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
      target(update.entityId)(update.attribute) = (update.value, status)
      // ... and move the update to the back of the queue
      val queueKey = (update.entityId, update.attribute)
      updatedAttributes -= queueKey
      updatedAttributes += queueKey
      // note: we do not have to check the size of the queue here because
      // we replaced an item
    } else {
      // we do not have a value for this entity and this attribute yet
      // => add the value to the map...
      target(update.entityId) = Map(update.attribute -> (update.value, status))
      // ... and just add the queue key to the end of the queue ...
      val queueKey = (update.entityId, update.attribute)
      updatedAttributes += queueKey
      // ... and check the size of the queue
      if (updatedAttributes.size > size) {
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
    val attributes = ListBuffer(request.extraAttributes: _*)
    if (subjectId2UpdatedAttributes.contains(request.subjectId)) {
      val toAdd = subjectId2UpdatedAttributes(request.subjectId).toSeq
      for ((attribute, (value, status)) <- toAdd) {
        attributes += ((attribute, value))
        status match {
          case Normal =>
            debug(s"[Evaluation ${request.id}] Added the following cached attribute because of ongoing updates on the SUBJECT: ($attribute,$value)")
          case Tentative =>
            try {
              tentativeAttributeUpdate2EvaluationIds((request.subjectId, attribute)) += request.id
            } catch {
              case e: NoSuchElementException =>
                debug(s"[Evaluation ${request.id}] NoSuchElementException for ($attribute,$value,$status)", e)
                throw e
            }
            debug(s"[Evaluation ${request.id}] Added the following TENTATIVELY cached attribute because of ongoing updates on the SUBJECT: ($attribute,$value)")
        }
      }
    }
    if (resourceId2UpdatedAttributes.contains(request.resourceId)) {
      val toAdd = resourceId2UpdatedAttributes(request.resourceId).toSeq
      for ((attribute, (value, status)) <- toAdd) {
        attributes += ((attribute, value))
        status match {
          case Normal =>
            debug(s"[Evaluation ${request.id}] Added the following cached attribute because of ongoing updates on the RESOURCE: ($attribute,$value)")
          case Tentative =>
            tentativeAttributeUpdate2EvaluationIds((request.resourceId, attribute)) += request.id
            debug(s"[Evaluation ${request.id}] Added the following TENTATIVELY cached attribute because of ongoing updates on the RESOURCE: ($attribute,$value)")
        }
      }
    }
    request.copy(extraAttributes = List(attributes: _*))
  }
}