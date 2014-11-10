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