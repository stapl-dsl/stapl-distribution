package stapl.distribution.db

import org.scalatest.junit.AssertionsForJUnit
import org.junit.Before
import org.junit.After
import org.junit.BeforeClass
import org.junit.Test
import org.junit.Assert._
import stapl.core.pdp.EvaluationCtx
import org.joda.time.LocalDateTime
import stapl.core._
import stapl.core.Attribute
import stapl.distribution.db.entities.ehealth.EhealthEntityManager
import stapl.core.pdp.AttributeFinder
import stapl.core.pdp.PDP
import stapl.examples.policies.EhealthPolicy
import scala.collection.mutable.ListBuffer
import stapl.core.pdp.ObligationService
import scala.collection.mutable.MultiMap

class MockAttributeDatabaseConnection extends AttributeDatabaseConnection {

  def cleanStart(): Unit = ???

  def commit(): Unit = ???

  def createTables(): Unit = ???

  def dropData(): Unit = ???

  def getStringAttribute(entityId: String, cType: stapl.core.AttributeContainerType, name: String): List[String] = ???

  def storeAttribute(entityId: String, cType: stapl.core.AttributeContainerType, name: String, value: String): Unit = ???

  def close() = ???

  val receivedAttributeUpdates = ListBuffer[(String, Attribute, Any)]()

  override def storeAnyAttribute(entityId: String, attribute: Attribute, value: Any) = {
    receivedAttributeUpdates += new Tuple3(entityId, attribute, value)
  }

  def updateAttribute(entityId: String, cType: AttributeContainerType, name: String, value: String): Unit = ???
}

class MockAttributeDatabaseConnectionPool extends AttributeDatabaseConnectionPool {

  override def getConnection = new MockAttributeDatabaseConnection
}

class InMemoryAttributeDatabaseConnection extends AttributeDatabaseConnection {

  import stapl.core.AttributeContainerType

  /**
   * Threadsafe!
   */
  private val attributes = new scala.collection.concurrent.TrieMap[(String, stapl.core.AttributeContainerType, String), String]

  def cleanStart(): Unit = dropData

  def commit(): Unit = {}

  def createTables(): Unit = {}

  def dropData(): Unit = attributes.clear

  def getStringAttribute(entityId: String, cType: AttributeContainerType, name: String): List[String] =
    attributes.get((entityId, cType, name)) match {
      case None => List()
      case Some(x) => List(x)
    }

  def storeAttribute(entityId: String, cType: AttributeContainerType, name: String, value: String): Unit =
    attributes((entityId, cType, name)) = value

  def close() = {}

  /**
   * Updates a string attribute in the database using the connection of this database.
   * Does NOT commit or close.
   */
  def updateAttribute(entityId: String, cType: AttributeContainerType, name: String, value: String): Unit =
    storeAttribute(entityId: String, cType: AttributeContainerType, name: String, value: String)
}

class InMemoryAttributeDatabaseConnectionPool extends AttributeDatabaseConnectionPool {

  val connection = new InMemoryAttributeDatabaseConnection

  def getConnection = connection
}