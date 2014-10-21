package stapl.distribution.test.rabbitmq

import com.hazelcast.config.Config
import com.hazelcast.config.MapConfig
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance

object Worker extends App {
  
  private val cfg = new Config();
  cfg.getNetworkConfig.getJoin.getMulticastConfig.setEnabled(false)
  cfg.getNetworkConfig.getJoin.getTcpIpConfig.setEnabled(true)
  cfg.getNetworkConfig.getJoin.getTcpIpConfig.addMember("127.0.0.1")
  private val hazelcast = Hazelcast.newHazelcastInstance(cfg)
  
  new Thread(new InternalRequestReceiver(hazelcast)).start

}

class InternalRequestReceiver(hazelcast: HazelcastInstance) extends Runnable {
  
  val id = java.util.UUID.randomUUID.toString
  
  hazelcast.getList[String]("workers").add(id)
  
  val in = hazelcast.getQueue[InternalPolicyEvaluationRequest](s"internal-requests-for-$id")
  val out = hazelcast.getQueue[InternalPolicyEvaluationResult]("internal-policy-evaluation-results")
  
  def run {
    while(true) {
      val request = in.take()
      //println(s"received internal request: $request")
      out.put(new InternalPolicyEvaluationResult(request.id, s"result-for-${request.request}"))
    }
  }
}