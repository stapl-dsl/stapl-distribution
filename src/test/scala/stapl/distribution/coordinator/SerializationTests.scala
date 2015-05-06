package stapl.distribution.coordinator

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
import com.hazelcast.nio.serialization.DefaultSerializationServiceBuilder
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

class SerializationTests extends AssertionsForJUnit {
    
    val subject = stapl.core.subject
	subject.string = SimpleAttribute(String)
	subject.strings = ListAttribute(String)
	subject.boolean = SimpleAttribute(Bool)
	subject.booleans = ListAttribute(Bool)
	subject.number = SimpleAttribute(Number)
	subject.numbers = ListAttribute(Number)
	subject.datetime = SimpleAttribute(DateTime)
	subject.datetimes = ListAttribute(DateTime)

  @Test def testSerialization {
    val config = new Config()
    config.setProperty(GroupProperties.PROP_WAIT_SECONDS_BEFORE_JOIN, "1")
    val h = Hazelcast.newHazelcastInstance(config)
    val customerMap = h.getMap("customers")
    val ss = new DefaultSerializationServiceBuilder().setConfig(config.getSerializationConfig()).build()
    val out = new ByteArrayOutputStream
    val objectOut = ss.createObjectDataOutputStream(out)

    val subject = stapl.core.subject
    subject.string = SimpleAttribute(String)
    subject.strings = ListAttribute(String)
    subject.boolean = SimpleAttribute(Bool)
    subject.booleans = ListAttribute(Bool)
    subject.number = SimpleAttribute(Number)
    subject.numbers = ListAttribute(Number)
    subject.datetime = SimpleAttribute(DateTime)
    subject.datetimes = ListAttribute(DateTime)

    val r1 = new ToBeEvaluatedRequest("maarten", "view", "doc123",
      subject.string -> "role1",
      subject.strings -> List("role1", "role2"),
      subject.boolean -> true,
      subject.booleans -> List(true, false, true),
      subject.number -> 1.23,
      subject.numbers -> List(1.23, 4.56),
      subject.datetime -> new LocalDateTime(2014, 6, 24, 9, 0, 0),
      subject.datetimes -> List(new LocalDateTime(2014, 6, 24, 9, 0, 0), new LocalDateTime(2014, 6, 24, 17, 0, 0)))

    val before = r1.toString
    println(before)

    r1.writeData(objectOut)

    val in = new ByteArrayInputStream(out.toByteArray())
    val objectIn = ss.createObjectDataInputStream(in)

    val r2 = new ToBeEvaluatedRequest("", "", "")
    r2.readData(objectIn)

    val after = r2.toString
    println(after)

    h.shutdown()
    
    assertEquals(before, after)
  }

  @Test def testAttributeValueSerializationString {
    val r = new ToBeEvaluatedRequest("", "", "")

    // TODO: major flaw in our current design: a lot of type errors can occur
    // in the lines below, for example Lists for SimpleAttributes, String for 
    // Number attributes etc. => now this is never checked in any of the code
    // Solution: add checks on the values or even better, use a type system.
    val attributeValue1: (Attribute, ConcreteValue) = (subject.string, "role1")
    val mock = new MockObjectDataOutput
    r.writeAttributeValue(attributeValue1, mock)
    assertEquals(mock.getCommands(), "Int: 0 - Int: 0 - UTF: string - Boolean: false - UTF: role1")
  }  
    
  @Test def testAttributeValueSerializationListString { 
    val r = new ToBeEvaluatedRequest("", "", "")
    val attributeValue: (Attribute, ConcreteValue) = (subject.strings, List("role1", "role2"))
    val mock = new MockObjectDataOutput
    r.writeAttributeValue(attributeValue, mock)
    assertEquals(mock.getCommands(), "Int: 0 - Int: 0 - UTF: strings - Boolean: true - Int: 2 - UTF: role1 - UTF: role2")
  }  
    
  @Test def testAttributeValueSerializationBoolean {     
    val r = new ToBeEvaluatedRequest("", "", "")
    val attributeValue: (Attribute, ConcreteValue) = (subject.boolean, true)    
    val mock = new MockObjectDataOutput
    r.writeAttributeValue(attributeValue, mock)
    assertEquals(mock.getCommands(), "Int: 0 - Int: 2 - UTF: boolean - Boolean: false - Boolean: true")
  } 
    
  @Test def testAttributeValueSerializationListBoolean {     
    val r = new ToBeEvaluatedRequest("", "", "")
    val attributeValue: (Attribute, ConcreteValue) = (subject.booleans, List(true, false, true))
    val mock = new MockObjectDataOutput
    r.writeAttributeValue(attributeValue, mock)
    assertEquals(mock.getCommands(), "Int: 0 - Int: 2 - UTF: booleans - Boolean: true - Int: 3 - Boolean: true - Boolean: false - Boolean: true") 
  } 
    
  @Test def testAttributeValueSerializationNumber {     
    val r = new ToBeEvaluatedRequest("", "", "")
    val attributeValue: (Attribute, ConcreteValue) = (subject.number, 1.23)
    val mock = new MockObjectDataOutput
    r.writeAttributeValue(attributeValue, mock)
    assertEquals(mock.getCommands(), "Int: 0 - Int: 1 - UTF: number - Boolean: false - Double: 1.23")
  } 
    
  @Test def testAttributeValueSerializationListNumber {     
    val r = new ToBeEvaluatedRequest("", "", "")
    val attributeValue: (Attribute, ConcreteValue) = (subject.numbers, List(1.23, 4.56))
    val mock = new MockObjectDataOutput
    r.writeAttributeValue(attributeValue, mock)
    assertEquals(mock.getCommands(), "Int: 0 - Int: 1 - UTF: numbers - Boolean: true - Int: 2 - Double: 1.23 - Double: 4.56")
  } 
    
  @Test def testAttributeValueSerializationDateTime {     
    val r = new ToBeEvaluatedRequest("", "", "")
    val attributeValue: (Attribute, ConcreteValue) = (subject.datetime, new LocalDateTime(2014, 6, 24, 9, 0, 0))
    val mock = new MockObjectDataOutput
    r.writeAttributeValue(attributeValue, mock)
    assertEquals(mock.getCommands(), "Int: 0 - Int: 3 - UTF: datetime - Boolean: false - UTF: 2014-06-24T09:00:00.000")
  } 
    
  @Test def testAttributeValueSerializationListDateTime {     
    val r = new ToBeEvaluatedRequest("", "", "")
    val attributeValue: (Attribute, ConcreteValue) = (subject.datetimes, List(new LocalDateTime(2014, 6, 24, 9, 0, 0), new LocalDateTime(2014, 6, 24, 17, 0, 0)))
    val mock = new MockObjectDataOutput
    r.writeAttributeValue(attributeValue, mock)
    assertEquals(mock.getCommands(), "Int: 0 - Int: 3 - UTF: datetimes - Boolean: true - Int: 2 - UTF: 2014-06-24T09:00:00.000 - UTF: 2014-06-24T17:00:00.000")
  } 
}