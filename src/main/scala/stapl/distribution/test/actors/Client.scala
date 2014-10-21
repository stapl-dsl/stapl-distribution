package stapl.distribution.test.actors

import com.hazelcast.config.Config
import com.hazelcast.config.MapConfig
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import java.util.concurrent.TimeUnit

object Client extends App {

  private val cfg = new Config();
  cfg.getNetworkConfig.getJoin.getMulticastConfig.setEnabled(false)
  cfg.getNetworkConfig.getJoin.getTcpIpConfig.setEnabled(true)
  cfg.getNetworkConfig.getJoin.getTcpIpConfig.addMember("127.0.0.1")
  private val hazelcast = Hazelcast.newHazelcastInstance(cfg)

  println("Starting client threads")
  val start = System.nanoTime()
  for (i <- 1 to 1) {
    new Thread(new LoadGenerator(s"$i", hazelcast, start)).start
  }
}

class PolicyEvaluationRequestFromClient(val clientId: String, val request: String) extends Serializable {
  override def toString: String = s"$clientId:$request"
}

class LoadGenerator(id: String, hazelcast: HazelcastInstance, start: Long) extends Runnable {

  val out = hazelcast.getQueue[PolicyEvaluationRequestFromClient]("policy-evaluation-requests")
  val in = hazelcast.getQueue[String](s"decisions-for-$id")

  val timer = new Timer

  private def printDuration {
    val stop = System.nanoTime()
    val ms = (stop.toDouble - start.toDouble) / 1000000.0
    println(s"stopped after = ${ms}ms (mean latency = ${timer.mean})")
  }

  def run {
    for (i <- 1 to 1000) {
      timer.time {
        out.put(new PolicyEvaluationRequestFromClient(id, s"$id:$i"))
        val result = Option(in.poll(3, TimeUnit.SECONDS))
        result match {
          case None => println(s"!!!!!!!! NO RESULT RECEIVED FOR $id:$i")
          case Some(result) => //println(s"received result: $result") 
        }
      }
    }
    printDuration
  }
}