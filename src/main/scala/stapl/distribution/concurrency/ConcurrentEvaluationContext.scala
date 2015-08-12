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
package stapl.distribution.concurrency

import stapl.core.pdp.RequestCtx
import stapl.core.pdp.RemoteEvaluator
import stapl.core.pdp.AttributeFinder
import stapl.core.Attribute
import stapl.core.ConcreteValue
import stapl.core.AttributeNotFoundException
import stapl.core.pdp.EvaluationCtx
import grizzled.slf4j.Logging
import stapl.distribution.concurrency.ConcurrentAttributeCache

/**
 * An evaluation context that applies a concurrent attribute cache.
 */
//class ConcurrentEvaluationContext(
//  override val evaluationId: Long,
//  val request: RequestCtx,
//  val finder: AttributeFinder,
//  override val remoteEvaluator: RemoteEvaluator,
//  val concurrentAttributeCache: ConcurrentAttributeCache)
//  extends EvaluationCtx with Logging {
//
//  override val subjectId: String = request.subjectId
//
//  override val resourceId: String = request.resourceId
//
//  override val actionId: String = request.actionId
//
//  override def cachedAttributes = concurrentAttributeCache.getAll(evaluationId)
//
//  /**
//   * Try to find the value of the given attribute. If the value is already
//   * in the attribute cache, that value is returned. Otherwise, the attribute
//   * finder is checked and the found value is stored in the attribute cache if
//   * a value is found.
//   */
//  override def findAttribute(attribute: Attribute): ConcreteValue = {
//    concurrentAttributeCache.get(evaluationId, attribute) match {
//      case Some(value) => {
//        debug("FLOW: found value of " + attribute + " in cache: " + value)
//        value
//      }
//      case None => { // Not in the cache
//        try { // Not in the cache
//          finder.find(this, attribute) match {
//            case None =>
//              debug(s"Didn't find value of $attribute anywhere, throwing exception")
//              throw new AttributeNotFoundException(attribute)
//            case Some(value) =>
//              concurrentAttributeCache.store(evaluationId, attribute, value)
//              debug("FLOW: retrieved value of " + attribute + ": " + value + " and added to cache")
//              value
//          }
//        } catch {
//          case e: AttributeNotFoundException =>
//            debug(s"Didn't find value of $attribute anywhere, exception thrown")
//            throw e
//          case e: Exception =>
//            debug(s"Unknown exception thrown: $e")
//            throw e
//        }
//      }
//    }
//  }
//}