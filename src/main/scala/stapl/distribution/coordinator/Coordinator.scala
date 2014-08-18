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

object CoordinatorTest {
  
  def main(args: Array[String]) {
    val subject = stapl.core.subject
    subject.string = SimpleAttribute(String)
    subject.strings = ListAttribute(String)
    subject.boolean = SimpleAttribute(Bool)
    subject.booleans = ListAttribute(Bool)
    subject.number = SimpleAttribute(Number)
    subject.numbers = ListAttribute(Number)
    subject.datetime = SimpleAttribute(DateTime)
    subject.datetimes = ListAttribute(DateTime)
    
    val r = new ToBeEvaluatedRequest("","","")
    
    println("String")
    val attributeValue1: (Attribute, ConcreteValue) = (subject.string, "role1")
    r.writeAttributeValue(attributeValue1, new MockObjectDataOutput)
    println("List[String]")
    val attributeValue2: (Attribute, ConcreteValue) = (subject.strings, List("role1","role2"))
    r.writeAttributeValue(attributeValue2, new MockObjectDataOutput)
    println("Boolean")
    val attributeValue3: (Attribute, ConcreteValue) = (subject.boolean, true)
    r.writeAttributeValue(attributeValue3, new MockObjectDataOutput)
    println("List[Boolean]")
    val attributeValue4: (Attribute, ConcreteValue) = (subject.booleans, List(true, false, true))
    r.writeAttributeValue(attributeValue4, new MockObjectDataOutput)
    println("Number")
    val attributeValue5: (Attribute, ConcreteValue) = (subject.number, 1.23)
    r.writeAttributeValue(attributeValue5, new MockObjectDataOutput)
    println("List[Number]")
    val attributeValue6: (Attribute, ConcreteValue) = (subject.numbers, List(1.23, 4.56))
    r.writeAttributeValue(attributeValue6, new MockObjectDataOutput)
    println("DateTime")
    val attributeValue7: (Attribute, ConcreteValue) = (subject.datetime, new LocalDateTime(2014, 6, 24, 9, 0, 0))
    r.writeAttributeValue(attributeValue7, new MockObjectDataOutput)
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
class ToBeEvaluatedRequest(subjectId: String, actionId: String,
  resourceId: String, extraAttributes: (Attribute, ConcreteValue)*) extends DataSerializable {

  def readData(in: ObjectDataInput): Unit = {

  }

  def writeData(out: ObjectDataOutput): Unit = {
    out.writeUTF(subjectId)
    out.writeUTF(actionId)
    out.writeUTF(resourceId)
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

  val attributeType2Int = Map[AttributeType, Int](
    String -> 0,
    Number -> 1,
    Bool -> 2,
    DateTime -> 3 // TODO the rest is not supported yet
    )
}