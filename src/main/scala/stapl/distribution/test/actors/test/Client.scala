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
import scala.concurrent.Await
import stapl.core.Decision
import stapl.core.Deny
import stapl.core.Permit
import akka.pattern.AskTimeoutException
import akka.routing.BroadcastPool

object ClientApp {
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
        import ClientProtocol._
        val clients = system.actorOf(BroadcastPool(nbThreads).props(Props(classOf[Client],coordinator)), "clients")
        clients ! Go(2000)
        println(s"$nbThreads client threads up and running at $hostname:$port")
      case Failure(t) =>
        t.printStackTrace()
        system.shutdown
    }
  }
}

class Client(coordinator: ActorRef) extends Actor with ActorLogging {

  import ClientProtocol._
  import ClientCoordinatorProtocol._

  implicit val timeout = Timeout(2.second)
  implicit val ec = context.dispatcher

  def receive = {
    case Go(nbRuns) => // launch a test of 100 messages 
      val timer = new Timer
      var counter = 0
      while(true) {
      //for (i <- 0 to nbRuns) {
        timer.time {
          val f = coordinator ? AuthorizationRequest(s"subject-of-client", "view", "doc123")
          val decision: Decision = Await.ready(f, 3 seconds).value match {
            case None =>
              // should never happen, but just in case...
              println("WTF: None received from ping???")
              Deny
            case Some(result) => result match {
              case Success(AuthorizationDecision(decision)) => 
                log.debug(s"Result of policy evaluation was: $decision")
                decision
              case Success(x) => 
                log.warning(s"No idea what I received here: $x => default deny")
                Deny
              case Failure(e: AskTimeoutException) =>
                log.warning("Timeout => default deny")
                Deny
              case Failure(e) =>
                log.warning("Some other failure? => default deny anyway")
                Deny
            }
          }
        }
        counter += 1
        if(counter % 100 == 0) {
          println(s"Mean latency of the last 100 requests: ${timer.mean}ms")
          timer.reset
        }
      }
      log.info(s"Client finished: $nbRuns requests in ${timer.total} ms (mean: ${timer.mean} ms)")
    case msg => log.error(s"Received unknown message: $msg")
  }

  log.info(s"Client created: $this")
}