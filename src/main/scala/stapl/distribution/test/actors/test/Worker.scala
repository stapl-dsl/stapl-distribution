package stapl.distribution.test.actors.test

import akka.actor.ActorPath
import akka.actor.ActorLogging
import akka.actor.Actor
import akka.actor.ActorRef
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import akka.actor.Props
import scala.concurrent.duration._
import scala.util.{ Success, Failure }

object WorkerApp {

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

abstract class Worker(coordinator: ActorRef)
  extends Actor with ActorLogging {
  import CoordinatorWorkerProtocol._

  /**
   *  This is how our derivations will interact with us.  It
   *  allows derivations to complete work asynchronously
   */
  case class WorkComplete(result: Any)

  /**
   *  Required to be implemented
   */
  def doWork(workSender: ActorRef, work: Any): Unit

  /**
   *  Notify the Master that we're alive
   */
  override def preStart() = coordinator ! WorkerCreated(self)

  /**
   *  This is the state we're in when we're working on something.
   *  In this state we can deal with messages in a much more
   *  reasonable manner.
   */
  def working(work: Any): Receive = {
    case WorkIsReady => // Pass... we're already working
    case NoWorkToBeDone => // Pass... we're already working
    case WorkToBeDone(_) => // Pass... we shouldn't even get this 
      log.error("Yikes. Master told me to do work, while I'm working.")
    case WorkComplete(result) => // Our derivation has completed its task
      log.debug("Work is complete.  Result {}.", result)
      coordinator ! WorkIsDone(self) // TODO kan dit niet samengevoegd worden?
      coordinator ! WorkerRequestsWork(self)
      // We're idle now
      context.become(idle)
  }

  /**
   *  In this state we have no work to do.  There really are only
   *  two messages that make sense while we're in this state, and
   *  we deal with them specially here.
   */
  def idle: Receive = {
    case WorkIsReady => // Coordinator says there's work to be done, let's ask for it
      log.debug("Requesting work")
      coordinator ! WorkerRequestsWork(self)
    case WorkToBeDone(work) => // Send the work off to the implementation
      log.debug("Got work {}", work)
      doWork(sender, work)
      context.become(working(work))
    case NoWorkToBeDone => // We asked for it, but either someone else got it first, or
    // there's literally no work to be done
  }

  def receive = idle
}