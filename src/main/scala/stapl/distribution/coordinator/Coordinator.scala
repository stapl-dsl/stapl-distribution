package stapl.distribution.coordinator

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

object CoordinatorTest {

  def main(args: Array[String]) {
	  testSerialization    
  }
  
  def testSerialization {
    val config = new Config()
    config.setProperty(GroupProperties.PROP_WAIT_SECONDS_BEFORE_JOIN, "1")
    val h = Hazelcast.newHazelcastInstance(config)
    val customerMap = h.getMap("customers")
    val ss = new SerializationServiceBuilder().setConfig(config.getSerializationConfig()).build()
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
    
    println("*** Before")
    println(r1.toString)
    
    r1.writeData(objectOut)
    
    val in = new ByteArrayInputStream(out.toByteArray())
    val objectIn = ss.createObjectDataInputStream(in)
    
    val r2 = new ToBeEvaluatedRequest("", "", "")
    r2.readData(objectIn)
    
    println
    println("*** After")
    println(r2.toString)
    
    h.shutdown()
  }

  def testAttributeValueSerialization {
    val subject = stapl.core.subject
    subject.string = SimpleAttribute(String)
    subject.strings = ListAttribute(String)
    subject.boolean = SimpleAttribute(Bool)
    subject.booleans = ListAttribute(Bool)
    subject.number = SimpleAttribute(Number)
    subject.numbers = ListAttribute(Number)
    subject.datetime = SimpleAttribute(DateTime)
    subject.datetimes = ListAttribute(DateTime)

    val r = new ToBeEvaluatedRequest("", "", "")

    // TODO: major flaw in our current design: a lot of type errors can occur
    // in the lines below, for example Lists for SimpleAttributes, String for 
    // Number attributes etc. => now this is never checked in any of the code
    // Solution: add checks on the values or even better, use a type system.
    println("String")
    val attributeValue1: (Attribute, ConcreteValue) = (subject.string, "role1")
    r.writeAttributeValue(attributeValue1, new MockObjectDataOutput)
    println
    println("List[String]")
    val attributeValue2: (Attribute, ConcreteValue) = (subject.strings, List("role1", "role2"))
    r.writeAttributeValue(attributeValue2, new MockObjectDataOutput)
    println
    println("Boolean")
    val attributeValue3: (Attribute, ConcreteValue) = (subject.boolean, true)
    r.writeAttributeValue(attributeValue3, new MockObjectDataOutput)
    println
    println("List[Boolean]")
    val attributeValue4: (Attribute, ConcreteValue) = (subject.booleans, List(true, false, true))
    r.writeAttributeValue(attributeValue4, new MockObjectDataOutput)
    println
    println("Number")
    val attributeValue5: (Attribute, ConcreteValue) = (subject.number, 1.23)
    r.writeAttributeValue(attributeValue5, new MockObjectDataOutput)
    println
    println("List[Number]")
    val attributeValue6: (Attribute, ConcreteValue) = (subject.numbers, List(1.23, 4.56))
    r.writeAttributeValue(attributeValue6, new MockObjectDataOutput)
    println
    println("DateTime")
    val attributeValue7: (Attribute, ConcreteValue) = (subject.datetime, new LocalDateTime(2014, 6, 24, 9, 0, 0))
    r.writeAttributeValue(attributeValue7, new MockObjectDataOutput)
    println
    println("List[DateTime]")
    val attributeValue8: (Attribute, ConcreteValue) = (subject.datetimes, List(new LocalDateTime(2014, 6, 24, 9, 0, 0), new LocalDateTime(2014, 6, 24, 17, 0, 0)))
    r.writeAttributeValue(attributeValue8, new MockObjectDataOutput)
  }
}

/**
 * Class used for representing the coordinator PDP. This coordinator should
 * be started first and all other slave PDPs should connect to this one.
 */
class Coordinator {

  private val cfg = new Config();
  cfg.getNetworkConfig.getJoin.getMulticastConfig.setEnabled(false)
  cfg.getNetworkConfig.getJoin.getTcpIpConfig.setEnabled(true)
  // we don't join any other nodes, since this one is the first
  private val queueCfg = new QueueConfig();
  queueCfg.setName("to-be-evaluated");
  queueCfg.setBackupCount(0);
  queueCfg.setMaxSize(0) // infinite
  queueCfg.setStatisticsEnabled(true)
  cfg.addQueueConfig(queueCfg)
  private val hazelcast = Hazelcast.newHazelcastInstance(cfg)

  val toBeEvaluatedQueue = hazelcast.getQueue("to-be-evaluated")

  /**
   * Initiate policy evaluation with the given ids and attributes.
   */
  def evaluate(subjectId: String, actionId: String,
    resourceId: String, extraAttributes: (Attribute, ConcreteValue)*): Result = {
    null
    //toBeEvaluatedQueue.put(new ToBeEvaluatedRequest(subjectId, actionId, resourceId, extraAttributes: _*))
  }

}

/**
 * Class used for representing, serializing and deserializing a request for a
 * policy evaluation.
 */
class ToBeEvaluatedRequest(private var _subjectId: String, private var _actionId: String,
  private var _resourceId: String, __extraAttributes: (Attribute, ConcreteValue)*) extends DataSerializable {

  def subjectId = _subjectId

  def actionId = _actionId

  def resourceId = _resourceId

  var _extraAttributes = List(__extraAttributes: _*)
  def extraAttributes = _extraAttributes
  
  override def toString(): String = {
    s"$subjectId, $actionId, $resourceId, $extraAttributes"
  }

  def readData(in: ObjectDataInput): Unit = {
    _subjectId = in.readUTF()
    _actionId = in.readUTF()
    _resourceId = in.readUTF()
    val nbAttributes = in.readInt()
    _extraAttributes = List()
    for (i <- 0 until nbAttributes) {
      _extraAttributes ::= readAttributeValue(in)
    }
    _extraAttributes = _extraAttributes.reverse
  }

  def readAttributeValue(in: ObjectDataInput): (Attribute, ConcreteValue) = {
    val containerType = int2AttributeContainerType.get(in.readInt()).get
    val attributeType = int2AttributeType.get(in.readInt()).get
    val name = in.readUTF()
    val isList = in.readBoolean()

    // here we can already construct the Attribute
    val attribute = if (isList) {
      new ListAttribute(containerType, name, attributeType)
    } else {
      new SimpleAttribute(containerType, name, attributeType)
    }

    if (isList) {
      val length = in.readInt()
      attributeType match {
        case String => {
          var values = List[String]()
          for (i <- 0 until length) {
            values ::= in.readUTF()
          }
          (attribute, new StringSeqImpl(values))
        }
        case Number => {
          var values = List[Double]()
          for (i <- 0 until length) {
            values ::= in.readDouble()
          }
          (attribute, new DoubleSeqImpl(values))
        }
        case Bool => {
          var values = List[Boolean]()
          for (i <- 0 until length) {
            values ::= in.readBoolean()
          }
          (attribute, new BoolSeqImpl(values))
        }
        case DateTime => {
          var values = List[DateTimeImpl]()
          for (i <- 0 until length) {
            values ::= new DateTimeImpl(new LocalDateTime(in.readUTF()))
          }
          (attribute, new DateTimeSeqImpl(values))
        }
        case _ => throw new UnsupportedOperationException
        // TODO the rest is not supported yet
      }
    } else {
      attributeType match {
        case String => {
          val value: ConcreteValue = in.readUTF()
          (attribute, value)
        }
        case Number => {
          val value: ConcreteValue = in.readDouble()
          (attribute, value)
        }
        case Bool => {
          val value: ConcreteValue = in.readBoolean()
          (attribute, value)
        }
        case DateTime => {
          val raw = in.readUTF()
          val value: ConcreteValue = new LocalDateTime(raw)
          (attribute, value)
        }
        case _ => throw new UnsupportedOperationException
        // TODO the rest is not supported yet
      }
    }
  }

  def writeData(out: ObjectDataOutput): Unit = {
    out.writeUTF(subjectId)
    out.writeUTF(actionId)
    out.writeUTF(resourceId)
    out.writeInt(extraAttributes.length)
    for (attribute <- extraAttributes) {
      writeAttributeValue(attribute, out)
    }
  }

  def writeAttributeValue(attributeValue: (Attribute, ConcreteValue), out: ObjectDataOutput) {
    val (attribute, value) = attributeValue
    // Attribute container type: serialize as an integer
    out.writeInt(attributeContainerType2Int.get(attribute.cType).get)
    // Attribute type: serialize as an integer
    out.writeInt(attributeType2Int.get(attribute.aType).get)
    // Attribute name: serialize as UTF
    out.writeUTF(attribute.name)
    // Attribute multiplicity: serialize as boolean
    out.writeBoolean(attribute.isList)
    // The attribute value is harder: depending on the attribute type and
    // multiplicity we have to serialize multiple values of a certain type.
    if (attribute.isList) {
      out.writeInt(value.asInstanceOf[SeqValue].length)
      attribute.aType match {
        case String => {
          val casted = value.asInstanceOf[StringSeqImpl]
          val values = casted.representation.asInstanceOf[Seq[String]]
          values.foreach(x => out.writeUTF(x))
        }
        case Number => {
          val casted = value.asInstanceOf[DoubleSeqImpl]
          val values = casted.representation.asInstanceOf[Seq[Double]]
          values.foreach(x => out.writeDouble(x))
        }
        case Bool => {
          val casted = value.asInstanceOf[BoolSeqImpl]
          val values = casted.representation.asInstanceOf[Seq[Boolean]]
          values.foreach(x => out.writeBoolean(x))
        }
        case DateTime => {
          val casted = value.asInstanceOf[DateTimeSeqImpl]
          val values = casted.representation.asInstanceOf[Seq[DateTimeImpl]]
          values.foreach(x => out.writeUTF(x.dt.toString()))
        }
        case _ => throw new UnsupportedOperationException
        // TODO the rest is not supported yet
      }
    } else {
      attribute.aType match {
        case String => {
          val casted = value.asInstanceOf[StringImpl]
          val v = casted.representation.asInstanceOf[String]
          out.writeUTF(v)
        }
        case Number => {
          val casted = value.asInstanceOf[NumberImpl]
          val v = casted.representation.asInstanceOf[Double]
          out.writeDouble(v)
        }
        case Bool => {
          val casted = value.asInstanceOf[BoolImpl]
          val v = casted.representation.asInstanceOf[Boolean]
          out.writeBoolean(v)
        }
        case DateTime => {
          val casted = value.asInstanceOf[DateTimeImpl]
          val v = casted.dt.asInstanceOf[LocalDateTime]
          out.writeUTF(v.toString())
        }
        case _ => throw new UnsupportedOperationException
        // TODO the rest is not supported yet
      }
    }
  }

  /**
   * Some serialization helpers.
   *
   * TODO: implement this nicely as methods in the classes themselves.
   */

  val attributeContainerType2Int = Map[AttributeContainerType, Int](
    SUBJECT -> 0,
    ACTION -> 1,
    RESOURCE -> 2,
    ENVIRONMENT -> 3)

  val int2AttributeContainerType = Map[Int, AttributeContainerType](
    0 -> SUBJECT,
    1 -> ACTION,
    2 -> RESOURCE,
    3 -> ENVIRONMENT)

  val attributeType2Int = Map[AttributeType, Int](
    String -> 0,
    Number -> 1,
    Bool -> 2,
    DateTime -> 3 // TODO the rest is not supported yet
    )

  val int2AttributeType = Map[Int, AttributeType](
    0 -> String,
    1 -> Number,
    2 -> Bool,
    3 -> DateTime)
}