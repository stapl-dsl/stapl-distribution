package stapl.distribution.components

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
import util.control.Breaks._
import stapl.distribution.db.entities.ehealth.EntityManager
import stapl.distribution.components.ClientCoordinatorProtocol._
import stapl.distribution.util.EvaluationEnded
import stapl.distribution.util.EvaluationEnded
import stapl.distribution.util.Counter

class SequentialClient(coordinator: ActorRef, request: AuthorizationRequest) extends Actor with ActorLogging {

  import ClientProtocol._

  implicit val timeout = Timeout(2.second)
  implicit val ec = context.dispatcher

  val timer = new Timer
  var counter = 0

  val em = EntityManager()

  def sendRequest = {
    timer.time {
      val f = coordinator ? request
      val decision: Decision = Await.ready(f, 180 seconds).value match {
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
            break
            Deny
          case Failure(e: AskTimeoutException) =>
            log.warning("Timeout => default deny")
            break
            Deny
          case Failure(e) =>
            log.warning("Some other failure? => default deny anyway")
            break
            Deny
        }
      }
    }
    counter += 1
    if (counter % 100 == 0) {
      println(s"Mean latency of the last 100 requests: ${timer.mean}ms")
      timer.reset
    }
  }

  def receive = {
    case Go(nb) => // launch a test of 100 messages 
      breakable {
        if (nb == 0) {
          // infinte loop
          while (true) {
            sendRequest
          }
        } else {
          for (i <- 1 to nb) {
            sendRequest
          }
        }
      }
      log.info(s"Client finished: $nb requests in ${timer.total} ms (mean: ${timer.mean} ms)")
    case msg => log.error(s"Received unknown message: $msg")
  }

  log.info(s"Sequential client created: $this")
}

class SequentialClientForConcurrentCoordinators(coordinators: RemoteConcurrentCoordinatorGroup, stats: ActorRef) extends Actor with ActorLogging {

  import ClientProtocol._

  implicit val timeout = Timeout(2.second)
  implicit val ec = context.dispatcher

  val timer = new Timer

  val em = EntityManager()

  def sendRequest = {
    timer.time {
      val request = AuthorizationRequest(em.randomSubject.id, "view", em.randomResource.id)
      val coordinator = coordinators.getCoordinatorFor(request)
      val f = coordinator ? request
      val decision: Decision = Await.ready(f, 180 seconds).value match {
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
            break
            Deny
          case Failure(e: AskTimeoutException) =>
            log.warning("Timeout => default deny")
            break
            Deny
          case Failure(e) =>
            log.warning("Some other failure? => default deny anyway")
            break
            Deny
        }
      }
    }
    stats ! EvaluationEnded(timer.mean) // note: we only use the timer for a single value
    timer.reset
  }

  def receive = {
    case Go(nb) => // launch a test of 100 messages 
      breakable {
        if (nb == 0) {
          // infinte loop
          while (true) {
            sendRequest
          }
        } else {
          for (i <- 1 to nb) {
            sendRequest
          }
        }
      }
      log.info(s"Client finished: $nb requests in ${timer.total} ms (mean: ${timer.mean} ms)")
    case msg => log.error(s"Received unknown message: $msg")
  }

  log.info(s"Sequential client created: $this")
}

class SequentialClientForCoordinatorGroup(coordinators: CoordinatorGroup, stats: ActorRef) extends Actor with ActorLogging {

  import ClientProtocol._

  implicit val timeout = Timeout(60.second)
  implicit val ec = context.dispatcher

  val timer = new Timer

  val em = EntityManager()

  val coordinatorCounter = new Counter("Different coordinators", 10000)

  def sendRequest = {
    timer.time {
      val request = AuthorizationRequest(em.randomSubject.id, "view", em.randomResource.id)
      val coordinator = coordinators.getCoordinatorFor(request)
      val f = coordinator ? request
      val decision: Decision = Await.ready(f, 180 seconds).value match {
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
            break
            Deny
          case Failure(e: AskTimeoutException) =>
            log.warning("Timeout => default deny")
            break
            Deny
          case Failure(e) =>
            log.warning("Some other failure? => default deny anyway")
            break
            Deny
        }
      }
    }
    stats ! EvaluationEnded(timer.mean) // note: we only use the timer for a single value
    timer.reset
  }

  def receive = {
    case Go(nb) => // launch a test of 100 messages 
      breakable {
        if (nb == 0) {
          // infinte loop
          while (true) {
            sendRequest
          }
        } else {
          for (i <- 1 to nb) {
            sendRequest
          }
        }
      }
      log.info(s"Client finished: $nb requests in ${timer.total} ms (mean: ${timer.mean} ms)")
    case msg => log.error(s"Received unknown message: $msg")
  }

  log.info(s"Sequential client created: $this")
}

class InitialPeakClient(coordinator: ActorRef, nb: Int) extends Actor with ActorLogging {

  import ClientProtocol._
  import ClientCoordinatorProtocol._

  implicit val timeout = Timeout(2.second)
  implicit val ec = context.dispatcher

  val timer = new Timer
  var waitingFor = nb

  val em = EntityManager()

  def receive = {
    case "go" =>
      timer.start
      for (i <- 1 to nb) {
        val f = coordinator ! AuthorizationRequest(em.maarten.id, "view", em.maartenStatus.id)
      }
    case AuthorizationDecision(decision) =>
      waitingFor -= 1
      if (waitingFor == 0) {
        timer.stop()
        log.info(s"Total duration of an initial peak of $nb requests = ${timer.duration}")
      }
    case x => log.error(s"Received unknown message: $x")
  }

  log.info(s"Intial peak client created: $this")
}

class InitialPeakClientForCoordinatorGroup(coordinators: CoordinatorGroup, nb: Int, stats: ActorRef) extends Actor with ActorLogging {

  import ClientProtocol._
  import ClientCoordinatorProtocol._

  implicit val timeout = Timeout(2.second)
  implicit val ec = context.dispatcher

  val timer = new Timer
  var waitingFor = nb

  val em = EntityManager()

  def receive = {
    case "go" =>
      timer.start
      for (i <- 1 to nb) {
        val request = AuthorizationRequest(em.randomSubject.id, "view", em.randomResource.id)
        val coordinator = coordinators.getCoordinatorFor(request)
        coordinator ! request
      }
    case AuthorizationDecision(decision) =>
      waitingFor -= 1
      stats ! EvaluationEnded() // note: the duration does not make sense for the IntialPeakClient
      log.debug(s"Waiting for: $waitingFor")
      if (waitingFor == 0) {
        timer.stop()
        log.info(f"Total duration of an initial peak of $nb requests = ${timer.duration}%2.0f ms")
      }
    case x => log.error(s"Received unknown message: $x")
  }

  log.info(s"Intial peak client created: $this")
}

class ContinuousOverloadClientForCoordinatorGroup(coordinators: CoordinatorGroup, nbRequests: Int, nbPeaks: Int, stats: ActorRef) extends Actor with ActorLogging {

  import ClientProtocol._
  import ClientCoordinatorProtocol._

  implicit val ec = context.dispatcher

  val timer = new Timer
  var waitingFor = nbRequests
  var peaksToDo = if (nbPeaks == 0) Double.PositiveInfinity else nbPeaks

  val em = EntityManager()

  val coordinatorCounter = new Counter("Different coordinators", 10000)

  def receive = {
    case "go" =>
      waitingFor = nbRequests
      timer.start
      for (i <- 1 to nbRequests) {
        val request = AuthorizationRequest(em.randomSubject.id, "view", em.randomResource.id)
        val coordinator = coordinators.getCoordinatorFor(request)
        coordinatorCounter.count(coordinator)
        coordinator ! request
      }
    case AuthorizationDecision(decision) =>
      waitingFor -= 1
      stats ! EvaluationEnded() // note: the duration does not make sense for the IntialPeakClient
      log.debug(s"$waitingFor")
      if (waitingFor == 0) {
        timer.stop()
        log.info(s"Total duration of a peak of $nbRequests requests = ${timer.duration}")
        peaksToDo -= 1
        // start another peak if we need to
        if (peaksToDo > 0) {
          log.debug("Starting the next peak")
          self ! "go"
        } else {
          log.debug("Done")
        }
      }
    case x => log.error(s"Received unknown message: $x")
  }

  log.info(s"Continuous overload client created: $this")
}

/**
 * A simple client for testing that only sends a request when the user indicates this.
 */
class TestClientForCoordinatorGroup(coordinators: CoordinatorGroup) extends Actor with ActorLogging {

  import ClientProtocol._
  import ClientCoordinatorProtocol._

  implicit val ec = context.dispatcher

  val em = EntityManager()

  val coordinatorCounter = new Counter("Different coordinators", 10000)

  def sendRequest() = {
    println("Provide the number of requests to send (default: 1)")
    var nb = 1
    try {
      nb = Console.readInt()
    } catch {
      case e: NumberFormatException =>
    }
    for (i <- 1 to nb) {
      val request = AuthorizationRequest(em.randomSubject.id, "view", em.randomResource.id)
      val coordinator = coordinators.getCoordinatorFor(request)
      coordinatorCounter.count(coordinator)
      coordinator ! request
    }
  }

  def receive = {
    case "go" =>
      sendRequest()
    case AuthorizationDecision(decision) =>
      log.debug(s"Received decision: $decision")
      sendRequest()
    case x => log.error(s"Received unknown message: $x")
  }

  log.info(s"Continuous overload client created: $this")
}