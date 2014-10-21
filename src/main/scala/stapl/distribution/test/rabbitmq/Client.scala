package stapl.distribution.test.rabbitmq

import com.hazelcast.config.Config
import com.hazelcast.config.MapConfig
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import java.util.concurrent.TimeUnit
import com.rabbitmq.client.ConnectionFactory
import collection.JavaConversions._

object Client extends App {

  println("Starting client threads")
  for (i <- 1 to 1) {
    new Thread(new LoadGenerator(s"$i")).start
  }
}

class PolicyEvaluationRequestFromClient(val clientId: String, val request: String) extends Serializable {
  override def toString: String = s"$clientId:$request"
  
  def bytes = toString.getBytes("UTF-8")
}

class LoadGenerator(id: String) extends Runnable {

  val QUEUE_NAME = "POLICY_EVALUATION_REQUESTS"

  val start = System.nanoTime()

  val factory = new ConnectionFactory()
  factory.setHost("localhost")
  val connection = factory.newConnection()
  val channel = connection.createChannel()
  channel.queueDeclare(QUEUE_NAME, false, false, false, null)

  val timer = new Timer

  private def printDuration {
    val stop = System.nanoTime()
    val ms = (stop.toDouble - start.toDouble) / 1000000.0
    println(s"stopped after = ${ms}ms (mean latency = ${timer.mean})")
  }

  def run {
    for (i <- 1 to 1000) {
//      timer.time {
//        val message = new PolicyEvaluationRequestFromClient(id, s"$id:$i")
//        channel.basicPublish("", QUEUE_NAME, null, message.bytes)
//        val result = Option(in.poll(3, TimeUnit.SECONDS))
//        result match {
//          case None => println(s"!!!!!!!! NO RESULT RECEIVED FOR $id:$i")
//          case Some(result) => //println(s"received result: $result") 
//        }
//      }
    }
    printDuration

    channel.close()
    connection.close()
  }
}