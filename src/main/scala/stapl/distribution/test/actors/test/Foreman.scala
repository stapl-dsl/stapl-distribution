package stapl.distribution.test.actors.test

import akka.actor.ActorPath
import akka.actor.ActorRef
import akka.dispatch.MessageDispatcher
import scala.concurrent.Future
import akka.pattern.pipe
import stapl.core.Permit
import stapl.core.Result
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import scala.concurrent.duration._
import scala.util.{ Success, Failure }
import akka.actor.Props

object ForemanApp {

  def main(args: Array[String]) {
    val workerName = args(0)
    val hostname = args.lift(1) match {
      case Some(h) => h
      case None => "127.0.0.1"
    }
    val port = args.lift(2) match {
      case Some(p) => p
      case None => 2552
    }

    val defaultConf = ConfigFactory.load()
    val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = $hostname
        akka.remote.netty.tcp.port = $port
      """).withFallback(defaultConf)
    val system = ActorSystem("Worker", customConf)

    val selection =
      system.actorSelection(s"akka.tcp://STAPL-coordinator@coordinator.stapl:2552/user/coordinator")
    implicit val dispatcher = system.dispatcher
    selection.resolveOne(3.seconds).onComplete {
      case Success(coordinator) =>
        val worker = system.actorOf(Props(classOf[Foreman], coordinator), "worker")
        println(s"Worker $workerName up and running at $hostname:$port")
      case Failure(t) =>
        t.printStackTrace()
        system.shutdown
    }
  }
}

class Foreman(coordinator: ActorRef) extends Worker(coordinator) {

  import CoordinatorWorkerProtocol._

  // We'll use the current dispatcher for the execution context.
  // You can use whatever you want.
  implicit val dispatcher = context.dispatcher

  /**
   * Evaluate the policy and notify the Coordinator of the result
   *
   * Note that this implementation actually does not need the workSender.
   */
  def doWork(workSender: ActorRef, work: Any): Unit = {
    // the work of this worker should always be a list of policy evaluation requests
    val requests = work.asInstanceOf[List[PolicyEvaluationRequest]]

    for (request <- requests) {
      // TODO insert actual policy evaluation here 
      // Do some work to simulate policy evaluation here
      for (i <- 0 until 5) { // 5 attributes
        // some computation (around 0.2ms)
        var factorial: BigInt = 0
        for (i <- 0 until 10000) {
          factorial *= i
        }
        // some attribute fetch
        Thread sleep 2
      }
      // TODO forward each request to a worker
      coordinator ! PolicyEvaluationResult(request.id, Result(Permit))
    }

    self ! WorkComplete("done")
  }
}