package stapl.distribution.test.actors.test

import akka.actor.ActorLogging
import akka.actor.Actor
import akka.actor.ActorPath
import akka.actor.ActorSystem
import akka.actor.Props
import scala.concurrent.duration._
import akka.actor.ActorRef
import com.typesafe.config.ConfigFactory
import scala.util.{ Success, Failure }
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.Future

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
      case Success(coordinator) =>
        import ClientProtocol._
        val client = system.actorOf(Props(classOf[Client], clientName, coordinator))
        println(s"Client $clientName up and running at $hostname:$port")
        client ! Go
      case Failure(t) =>
        t.printStackTrace()
        system.shutdown
    }
  }
}

class Client(id: String, coordinator: ActorRef) extends Actor with ActorLogging {

  import ClientProtocol._
  import ClientCoordinatorProtocol._

  implicit val timeout = Timeout(2.second)
  implicit val ec = context.dispatcher

  def receive = {
    case Go => // launch a test of 100 messages 
      log.info("Go")
      for (i <- 0 to 100) {
        val f: Future[Any] = coordinator ? AuthorizationRequest(s"subject-of-client-$id", "view", "doc123")
        f.onComplete {
          case Success(AuthorizationDecision(decision)) => log.info(s"Result of policy evaluation was: $decision")
          case Success(x) => log.warning(s"No idea what I received here: $x")
          case Failure(t) => 
            log.warning(s"Something went wrong: $t => default deny")            
        }
      }
    case msg => log.info(s"Received unknown message: $msg")
  }

  log.info(s"Client created: $this")

}