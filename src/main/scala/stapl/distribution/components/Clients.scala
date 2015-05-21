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
import stapl.distribution.db.entities.ehealth.EhealthEntityManager
import stapl.distribution.components.ClientCoordinatorProtocol._
import stapl.distribution.util.EvaluationEnded
import stapl.distribution.util.Counter
import stapl.distribution.db.entities.EntityManager
import stapl.distribution.util.PrintStats
import stapl.distribution.util.Trace

class SequentialClient(coordinator: ActorRef, request: AuthorizationRequest) extends Actor with ActorLogging {

  import ClientProtocol._

  implicit val timeout = Timeout(2.second)
  implicit val ec = context.dispatcher

  val timer = new Timer
  var counter = 0

  def sendRequest = {
    timer.time {
      val f = coordinator ? request
      val decision: Decision = Await.ready(f, 180 seconds).value match {
        case None =>
          // should never happen, but just in case...
          println("WTF: None received from ping???")
          Deny
        case Some(result) => result match {
          case Success(AuthorizationDecision(id, decision)) =>
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
          // infinite loop
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

class SequentialClientForConcurrentCoordinators(coordinators: RemoteConcurrentCoordinatorGroup,
  em: EntityManager, stats: ActorRef) extends Actor with ActorLogging {

  import ClientProtocol._

  implicit val timeout = Timeout(2.second)
  implicit val ec = context.dispatcher

  val timer = new Timer

  def sendRequest = {
    val request = em.randomRequest
    val coordinator = coordinators.getCoordinatorFor(request)
    timer.time {
      val f = coordinator ? request
      val decision: Decision = Await.ready(f, 180 seconds).value match {
        case None =>
          // should never happen, but just in case...
          println("WTF: None received from ping???")
          Deny
        case Some(result) => result match {
          case Success(AuthorizationDecision(id, decision)) =>
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
            if (i % 1000 == 0) {
              println(s"Run $i")
            }
            sendRequest
          }
        }
      }
      log.info(s"Client finished: $nb requests in ${timer.total} ms (mean: ${timer.mean} ms)")
    case msg => log.error(s"Received unknown message: $msg")
  }

  log.info(s"Sequential client created: $this")
}

class SequentialClientForCoordinatorGroup(coordinators: CoordinatorGroup,
  em: EntityManager, stats: ActorRef) extends Actor with ActorLogging {

  import ClientProtocol._

  implicit val timeout = Timeout(60.second)
  implicit val ec = context.dispatcher

  val timer = new Timer

  val coordinatorCounter = new Counter("Different coordinators", 10000, false)

  def sendRequest(measure: Boolean = true) = {
    val request = em.randomRequest
    val coordinator = coordinators.getCoordinatorFor(request)
    timer.time {
      val f = coordinator ? request
      val decision: Decision = Await.ready(f, 180 seconds).value match {
        case None =>
          // should never happen, but just in case...
          println("WTF: None received from ping???")
          Deny
        case Some(result) => result match {
          case Success(AuthorizationDecision(id, decision)) =>
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
    if (measure) {
      stats ! EvaluationEnded(timer.last)
    }
  }

  def receive = {
    case (nbWarmups: Int, nbRuns: Int) =>
      // warmup requests
      println(s"Doing $nbWarmups warmup requests")
      breakable {
        for (i <- 1 to nbWarmups) {
          sendRequest(false)
        }
      }

      // measured requests
      println(s"Doing $nbRuns requests")
      breakable {
        if (nbRuns == 0) {
          // infinte loop
          while (true) {
            sendRequest()
          }
        } else {
          for (i <- 1 to nbRuns) {
            if (i % 1000 == 0) {
              println(s"Run $i")
            }
            sendRequest()
          }
        }
      }
      log.info(s"Client finished: $nbRuns requests in ${timer.total} ms (mean: ${timer.mean} ms)")
    case msg => log.error(s"Received unknown message: $msg")
  }

  log.info(s"Sequential client created: $this")
}

class InitialPeakClient(coordinator: ActorRef, nb: Int,
  em: EntityManager) extends Actor with ActorLogging {

  import ClientProtocol._
  import ClientCoordinatorProtocol._

  implicit val timeout = Timeout(2.second)
  implicit val ec = context.dispatcher

  val timer = new Timer
  var waitingFor = nb

  def receive = {
    case "go" =>
      timer.start
      for (i <- 1 to nb) {
        val f = coordinator ! em.randomRequest
      }
    case AuthorizationDecision(id, decision) =>
      waitingFor -= 1
      if (waitingFor == 0) {
        timer.stop()
        log.info(s"Total duration of an initial peak of $nb requests = ${timer.last}")
      }
    case x => log.error(s"Received unknown message: $x")
  }

  log.info(s"Intial peak client created: $this")
}

class InitialPeakClientForCoordinatorGroup(coordinators: CoordinatorGroup, nbWarmups: Int, nb: Int,
  em: EntityManager, stats: ActorRef) extends Actor with ActorLogging {

  import ClientProtocol._
  import ClientCoordinatorProtocol._

  implicit val timeout = Timeout(2.second)
  implicit val ec = context.dispatcher

  val timer = new Timer
  var waitingForWarmups = nbWarmups
  var waitingFor = nb

  // the sender that we will notify when the peak has been processed
  var s: ActorRef = null

  def receive = {
    case "go" =>
      s = sender
      for (i <- 1 to nbWarmups) {
        val request = em.randomRequest
        val coordinator = coordinators.getCoordinatorFor(request)
        coordinator ! request
      }
    case "go-after-warmup" =>
      timer.start
      for (i <- 1 to nb) {
        val request = em.randomRequest
        val coordinator = coordinators.getCoordinatorFor(request)
        coordinator ! request
      }
    case AuthorizationDecision(id, decision) =>
      if (waitingForWarmups > 0) {
        // we are still in the warmup phase
        waitingForWarmups -= 1
        if (waitingForWarmups == 0) {
          self ! "go-after-warmup"
        }
      } else {
        waitingFor -= 1
        stats ! EvaluationEnded() // note: the duration does not make sense for the IntialPeakClient
        log.debug(s"Waiting for: $waitingFor")
        if (waitingFor == 0) {
          timer.stop()
          log.info(f"Total duration of an initial peak of $nb requests = ${timer.last}%2.0f ms => average of ${nb.toDouble / timer.last * 1000}%2.0f evaluations / sec")
          s ! "done"
        }
      }
    case x => log.error(s"Received unknown message: $x")
  }

  //log.info(s"Intial peak client created: $this")
}

class ContinuousOverloadClientForCoordinatorGroup(coordinators: CoordinatorGroup,
  em: EntityManager, nbRequests: Int, nbWarmupPeaks: Int, nbPeaks: Int, stats: ActorRef) extends Actor with ActorLogging {

  import ClientProtocol._
  import ClientCoordinatorProtocol._

  implicit val ec = context.dispatcher

  val timer = new Timer
  var waitingFor = nbRequests
  var warmupPeaksToDo = nbWarmupPeaks
  var atWarmup = if (nbWarmupPeaks > 0) true else false
  var peaksToDo = if (nbPeaks == 0) Double.PositiveInfinity else nbPeaks

  val coordinatorCounter = new Counter("Different coordinators", 10000, false)

  def receive = {
    case "go" =>
      waitingFor = nbRequests
      timer.start
      for (i <- 1 to nbRequests) {
        val request = em.randomRequest
        val coordinator = coordinators.getCoordinatorFor(request)
        coordinatorCounter.count(coordinator)
        coordinator ! request
      }
    case AuthorizationDecision(id, decision) =>
      waitingFor -= 1
      if (!atWarmup) {
        stats ! EvaluationEnded() // note: the duration does not make sense for the ContinuousOverloadClient
      }
      log.debug(s"$waitingFor")
      if (waitingFor == 0) {
        if (warmupPeaksToDo > 0) {
          // we're still in warmup
          warmupPeaksToDo -= 1
          if (warmupPeaksToDo == 0) {
            println("End of warm-up phase")
            atWarmup = false
            timer.stop
            timer.reset
          }
          self ! "go"
        } else {
          timer.stop()
          log.info(s"Total duration of a peak of $nbRequests requests = ${timer.last}")
          peaksToDo -= 1
          println(f"Done peak ${nbPeaks - peaksToDo}%.0f/$nbPeaks")
          // start another peak if we need to
          if (peaksToDo > 0) {
            log.debug("Starting the next peak")
            self ! "go"
          } else {
            log.debug("Done")
          }
        }
      }
    case x => log.error(s"Received unknown message: $x")
  }

  log.info(s"Continuous overload client created: $this")
}

/**
 * A simple client for testing that only sends a request when the user indicates this.
 */
class TestClientForCoordinatorGroup(coordinators: CoordinatorLocater,
  em: EntityManager) extends Actor with ActorLogging {

  import ClientProtocol._
  import ClientCoordinatorProtocol._

  implicit val timeout = Timeout(2.second)
  implicit val ec = context.dispatcher

  val coordinatorCounter = new Counter("Different coordinators", 10000, false)

  val timer = new Timer()

  val durationRegex = "^([0-9]+)ms$".r
  val numberRegex = "^([0-9]+)$".r

  def sendRequest(): Unit = {
    println("Provide the number of requests to send as a number (default: 1) or provide a duration to aim for in the form of \"<x>ms\"")

    Console.readLine match {
      case durationRegex(duration) =>
        val dur = duration.toInt
        var continue = true
        var nbTries = 0
        while (continue) {
          val request = em.randomRequest
          val coordinator = coordinators.getCoordinatorFor(request)
          coordinatorCounter.count(coordinator)
          val evaluationId = timer.time {
            Await.result(coordinator ? request, 180 seconds) match {
              case AuthorizationDecision(id, decision) =>
                id
              case x =>
                throw new RuntimeException(s"Something went wrong: $x")
            }
          }
          nbTries += 1
          if (timer.last > dur) {
            continue = false
            log.info(s"Received reply with duration of ${timer.last} ms after $nbTries tries")
            // collect and print the trace of this request
            val coordinatorForSubject = coordinators.getCoordinatorForSubject(request.subjectId)
            val traceStepsForSubject = Await.result(coordinatorForSubject ? TraceProtocol.GetTrace(evaluationId), 180 seconds) match {
              case TraceProtocol.Trace(steps) =>
                steps
              case x =>
                throw new RuntimeException(s"Something went wrong: $x")
            }
            val coordinatorForResource = coordinators.getCoordinatorForResource(request.resourceId)
            val traceStepsForResource = Await.result(coordinatorForResource ? TraceProtocol.GetTrace(evaluationId), 180 seconds) match {
              case TraceProtocol.Trace(steps) =>
                steps
              case x =>
                throw new RuntimeException(s"Something went wrong: $x")
            }
            val trace = new Trace(evaluationId)
            trace.steps ++= traceStepsForSubject
            trace.steps ++= traceStepsForResource
            trace.prettyPrint
          }
        }

      case numberRegex(number) =>
        val nb = number.toInt
        for (i <- 1 to nb) {
          val request = em.randomRequest
          val coordinator = coordinators.getCoordinatorFor(request)
          coordinatorCounter.count(coordinator)
          timer.time {
            Await.result(coordinator ? request, 180 seconds) match {
              case AuthorizationDecision(id, decision) =>
              case x =>
                throw new RuntimeException(s"Something went wrong: $x")
            }
          }
          log.info(s"Received reply, duration = ${timer.last} ms")
        }
      case _ =>
        println("I have no idea what you typed")
    }
    sendRequest
  }

  def receive = {
    case "go" =>
      sendRequest()
    case x => log.error(s"Received unknown message: $x")
  }

  log.info(s"Continuous overload client created: $this")
}