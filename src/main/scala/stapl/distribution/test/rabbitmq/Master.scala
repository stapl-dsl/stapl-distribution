package stapl.distribution.test.rabbitmq

import com.hazelcast.config.Config
import com.hazelcast.config.MapConfig
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.IQueue
import scala.collection.concurrent.Map
import scala.collection.concurrent.TrieMap
import com.hazelcast.core.ItemListener
import com.hazelcast.core.ItemEvent
import java.util.Random

object Master extends App {
  
  val messagesInProgress: Map[String,String] = new TrieMap
  
//  new Thread(new RequestReceiver(hazelcast, messagesInProgress)).start()
//  
//  new Thread(new ResultReceiver(hazelcast, messagesInProgress)).start()

}

class InternalPolicyEvaluationRequest(val id: String, val request: String) extends Serializable
class InternalPolicyEvaluationResult(val id: String, val result: String) extends Serializable

class IdManager {
  
  var id = 0
  
  def next = {
    val result = id
    id = id + 1
    s"$result"
  }
}

class ClientQueueManager(hazelcast: HazelcastInstance) {
  
  val queues: scala.collection.mutable.Map[String,IQueue[String]] = scala.collection.mutable.Map()
  
  def get(clientId: String) = {
    val q = queues.get(clientId)
    q match {
      case None => {
        val q = hazelcast.getQueue[String](s"decisions-for-$clientId")
        queues(clientId) = q
        q
      }
      case Some(q) => q
    }
  }
}

class WorkerManager(hazelcast: HazelcastInstance) {
  
  val workers = hazelcast.getList[String]("workers")
  
  def next: IQueue[InternalPolicyEvaluationRequest] = {
    val rand = new Random();
	val random_index = rand.nextInt(workers.size());
	val id = workers.get(random_index);
	hazelcast.getQueue[InternalPolicyEvaluationRequest](s"internal-requests-for-$id")
  }
}

class RequestReceiver(hazelcast: HazelcastInstance, messagesInProgress: Map[String,String]) extends Runnable {
  
  val in = hazelcast.getQueue[PolicyEvaluationRequestFromClient]("policy-evaluation-requests")
  val out = hazelcast.getQueue[InternalPolicyEvaluationRequest]("internal-policy-evaluation-requests")
  
  val idManager = new IdManager
  val workerManager = new WorkerManager(hazelcast)
  
  def run {
    while(true) {
      val request = in.take()
      //println(s"received request: $request")
      val id = idManager.next
      messagesInProgress(id) = request.clientId
      workerManager.next.put(new InternalPolicyEvaluationRequest(id, request.request))
    }       
  }
}

class ResultReceiver(hazelcast: HazelcastInstance, messagesInProgress: Map[String,String]) extends Runnable {
  
  val in = hazelcast.getQueue[InternalPolicyEvaluationResult]("internal-policy-evaluation-results")
  
  val cqm = new ClientQueueManager(hazelcast)
  
  def run {
    while(true) {
      val result = in.take()
      //println(s"received result: $result")
      val clientId = messagesInProgress(result.id)
      // Queue caching does not seem to have an effect on performance
      //cqm.get(clientId).put(result.result)
      hazelcast.getQueue[String](s"decisions-for-$clientId").put(result.result)
      messagesInProgress.remove(result.id, clientId)
    }
  }
  
}