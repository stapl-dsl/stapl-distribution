package stapl.distribution.test.actors.test

import akka.actor.ActorLogging
import akka.actor.Actor
import akka.actor.ActorPath
import akka.actor.ActorSystem
import akka.actor.Props
import scala.concurrent.duration._
import akka.actor.ActorRef
import com.typesafe.config.ConfigFactory
import scala.util.{Success, Failure}

object ClientApp {
  def main(args: Array[String]) {
    val clientName = args(0)
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
    val system = ActorSystem("STAPL-client", customConf)

    val selection =
      system.actorSelection(s"akka.tcp://STAPL-coordinator@coordinator.stapl:2552/user/coordinator")
    implicit val dispatcher = system.dispatcher
    selection.resolveOne(3.seconds).onComplete {
      case Success(master) =>
        import ClientMasterProtocol._
        val client = system.actorOf(Props(classOf[Client], clientName, master))
        println(s"Client $clientName up and running")
        client ! Go
      case Failure(t) =>
        t.printStackTrace()
        system.shutdown
    }
  }
}

class Client(id: String, master: ActorRef) extends Actor with ActorLogging {

  import ClientMasterProtocol._

  def receive = {
    case Go => // launch a test of 100 messages 
      log.info("Go")
      for (i <- 0 to 100) {
        master ! "gvd" //s"$id-$i"
      }
    case msg => log.info(s"Received $msg")
  }

  log.info(s"Client created: $this")

}