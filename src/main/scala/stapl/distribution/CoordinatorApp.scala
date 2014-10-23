package stapl.distribution

import akka.actor.ActorRef
import akka.actor.ActorLogging
import akka.actor.Actor
import akka.actor.Terminated
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import akka.actor.Props
import scala.collection.mutable.{ Map, Queue }
import stapl.distribution.components.Coordinator

object CoordinatorApp {

  def main(args: Array[String]) {
    val hostname = args.lift(0) match {
      case Some(h) => h
      case None => "127.0.0.1"
    }
    val port = args.lift(1) match {
      case Some(p) => p
      case None => 2552
    }

    val defaultConf = ConfigFactory.load()
    val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = $hostname
        akka.remote.netty.tcp.port = $port
      """).withFallback(defaultConf)
    val system = ActorSystem("STAPL-coordinator", customConf)

    val coordinator = system.actorOf(Props[Coordinator], "coordinator")

    println(s"Coordinator up and running at $hostname:2552")
  }
}