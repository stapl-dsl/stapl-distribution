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
package stapl.distribution.cache

import org.scalatest.junit.AssertionsForJUnit
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.Assert._
import com.hazelcast.config.Config
import com.hazelcast.config.QueueConfig
import com.hazelcast.core.Hazelcast
import stapl.core.pdp.EvaluationCtx
import com.hazelcast.nio.serialization.DataSerializable
import com.hazelcast.nio.ObjectDataOutput
import com.hazelcast.nio.ObjectDataInput
import org.joda.time.LocalDateTime
import stapl.core._
import stapl.core.Attribute
import com.hazelcast.instance.GroupProperties
import com.hazelcast.nio.serialization.SerializationServiceBuilder
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import stapl.distribution.concurrency.DistributedAttributeCache

class SerializationTests extends AssertionsForJUnit {

  @Test def testAttributeCache() {
    val subject = stapl.core.subject
    subject.roles = ListAttribute(String)
    subject.number = SimpleAttribute(Number)

    val attributeCache = new DistributedAttributeCache
    val result1 = attributeCache.store(1, subject.roles, List("role1"))
    assertEquals(result1, stringSeq2Value(List("role1")))
    assertEquals(attributeCache.getCompleteCache, Map("maarten.SUBJECT.roles:List[String]" -> List("role1")))
    val result2 = attributeCache.store(1,subject.number, 123)
    assertEquals(result2, int2Value(123))
    assertEquals(attributeCache.getCompleteCache, Map("maarten.SUBJECT.roles:List[String]" -> List("role1"), "maarten.SUBJECT.number:Number" -> 123))
    val result3 = attributeCache.store(1, subject.roles, List("role2")) // role1 should NOT be overwritten
    assertEquals(result2, stringSeq2Value(List("role1")))
    assertEquals(attributeCache.getCompleteCache, Map("maarten.SUBJECT.roles:List[String]" -> List("role1"), "maarten.SUBJECT.number:Number" -> 123))
  }
}