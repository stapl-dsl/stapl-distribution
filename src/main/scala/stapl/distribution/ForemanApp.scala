package stapl.distribution

import akka.actor.ActorRef
import stapl.core.Permit
import stapl.core.Result
import akka.actor.ActorSystem
import scala.concurrent.duration._
import scala.util.{ Success, Failure }
import akka.actor.Props
import akka.actor.actorRef2Scala
import scala.math.BigInt.int2bigInt
import stapl.distribution.components.Foreman
import com.typesafe.config.ConfigFactory

object ForemanApp {
  def main(args: Array[String]) {
    val name = args(0)
    val hostname = args.lift(1) match {
      case Some(h) => h
      case None => "127.0.0.1"
    }
    val port = args.lift(2) match {
      case Some(p) => p
      case None => 2552
    }
    val nbWorkers = args.lift(3) match {
      case Some(nb) => nb
      case None => 10
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
        val worker = system.actorOf(Props(classOf[Foreman], coordinator, nbWorkers), "foreman")
        println(s"Forman $name up and running at $hostname:$port with $nbWorkers workers")
      case Failure(t) =>
        t.printStackTrace()
        system.shutdown
    }
  }
}