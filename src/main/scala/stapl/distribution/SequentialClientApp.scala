package stapl.distribution

import akka.actor.ActorLogging
import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.Props
import scala.concurrent.duration._
import akka.actor.ActorRef
import com.typesafe.config.ConfigFactory
import scala.util.{ Success, Failure }
import akka.util.Timeout
import scala.concurrent.Await
import stapl.core.Decision
import stapl.core.Deny
import akka.pattern.AskTimeoutException
import akka.routing.BroadcastPool
import akka.actor.actorRef2Scala
import stapl.distribution.util.Timer
import akka.pattern.ask
import stapl.distribution.components.SequentialClient

object SequentialClientApp {
  def main(args: Array[String]) {
    val clientName = args(0)
    val nbThreads = args(1).toInt
    val hostname = args.lift(2) match {
      case Some(h) => h
      case None => "127.0.0.1"
    }
    val port = args.lift(3) match {
      case Some(p) => p
      case None => 2552
    }

    val defaultConf = ConfigFactory.load()
    val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = $hostname
        akka.remote.netty.tcp.port = $port
      """).withFallback(defaultConf)
    val system = ActorSystem("STAPL-client", customConf)

    val selection =
      system.actorSelection(s"akka.tcp://STAPL-coordinator@coordinator.stapl:2552/user/coordinator")
    implicit val dispatcher = system.dispatcher
    selection.resolveOne(3.seconds).onComplete {
      case Success(coordinator) =>
        import components.ClientProtocol._
        val clients = system.actorOf(BroadcastPool(nbThreads).props(Props(classOf[SequentialClient],coordinator)), "clients")
        clients ! Go(10000)
        println(s"$nbThreads client threads up and running at $hostname:$port")
      case Failure(t) =>
        t.printStackTrace()
        system.shutdown
    }
  }
}