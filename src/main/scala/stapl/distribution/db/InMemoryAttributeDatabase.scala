package stapl.distribution.db

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