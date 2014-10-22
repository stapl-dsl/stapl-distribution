package stapl.distribution.test.actors.test

import akka.actor.ActorRef
import akka.actor.ActorLogging
import akka.actor.Actor
import akka.actor.Terminated
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import akka.actor.Props
import scala.collection.mutable.{ Map, Queue }

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

/**
 * Class used for temporarily storing the clients that sent an
 * authorization request so that we can pass the decision back
 * to the client later on.
 */
class ClientAuthorizationRequestManager {

  import scala.collection.mutable.Map

  private var counter = 0

  private val clients: Map[Int, ActorRef] = Map()

  /**
   * Stores the actor in the cache and returns the generated
   * id of its authorization request.
   */
  def store(actor: ActorRef) = {
    val id = counter
    clients(id) = actor
    counter += 1
    id
  }

  /**
   * Returns the actor that sent the authorization request with the given id
   * and removes this actor from the map.
   */
  def get(id: Int) = {
    val result = clients(id)
    clients.remove(id)
    result
  }
}

/**
 * Class used for printing statistics about the coordinator.
 */
class Statistics {
  val totalStart = System.nanoTime()
  var intervalStart = System.nanoTime()

  var totalCounter = 0L
  var intervalCounter = 0L
  
  def tick() = {
    totalCounter += 1
    intervalCounter += 1L
    printThroughput
  }

  def totalCount = totalCounter
  def intervalCount = intervalCounter

  /**
   * The duration in ms
   */
  def totalDuration = {
    val now = System.nanoTime()
    (now.toDouble - totalStart.toDouble) / 1000000.0
  }
  def intervalDuration = {
    val now = System.nanoTime()
    (now.toDouble - intervalStart.toDouble) / 1000000.0
  }
  
  /**
   * In requests/sec
   */
  def totalThroughput = totalCount.toDouble / (totalDuration/1000)
  def intervalThroughput = intervalCount.toDouble / (intervalDuration/1000)
  
  def resetInterval = {
    intervalStart = System.nanoTime()
    intervalCounter = 0
  }

  def printThroughput {
    if (intervalCount % 1000 == 0 && intervalCount > 1000 && intervalDuration > 1000) {
      println(f"Coordinator: total throughput = $totalThroughput%2.2f requests/sec, last interval throughput = $intervalThroughput%2.2f")
      resetInterval
    }
  }
}

class Coordinator extends Actor with ActorLogging {
  import ClientCoordinatorProtocol._
  import CoordinatorWorkerProtocol._

  /**
   *  Holds known workers and what they may be working on
   */
  val workers = Map.empty[ActorRef, Option[Tuple2[ActorRef, List[PolicyEvaluationRequest]]]]

  /**
   * Holds the incoming list of work to be done as well
   * as the memory of who asked for it
   */
  val workQ = Queue.empty[Tuple2[ActorRef, PolicyEvaluationRequest]]

  /**
   * Holds the mapping between the clients and the authorization requests
   * they sent.
   */
  val clients = new ClientAuthorizationRequestManager
  
  /**
   * Some statistics of the throughput
   */
  val stats = new Statistics

  /**
   * Notifies workers that there's work available, provided they're
   * not already working on something
   */
  def notifyWorkers(): Unit = {
    if (!workQ.isEmpty) {
      workers.foreach {
        case (worker, m) if (m.isEmpty) => worker ! WorkIsReady
        case _ =>
      }
    }
  }

  def receive = {
    /**
     *  Worker is alive. Add him to the list, watch him for
     *  death, and let him know if there's work to be done
     */
    case WorkerCreated(worker) =>
      log.debug("Worker created: {}", worker)
      context.watch(worker)
      workers += (worker -> None)
      notifyWorkers()

    /**
     * 	A worker wants more work.  If we know about him, he's not
     *  currently doing anything, and we've got something to do,
     *  give it to him.
     */
    case WorkerRequestsWork(worker) =>
      log.debug("Worker requests work: {}", worker)
      if (workers.contains(worker)) {
        if (workQ.isEmpty)
          worker ! NoWorkToBeDone
        else if (workers(worker) == None) {
          val (workSender, work) = workQ.dequeue()
          workers += (worker -> Some(workSender -> List(work))) // TODO decide when to pass more than one evaluation request
          worker.tell(WorkToBeDone(List(work)), workSender) // Use the special form of 'tell' that lets us supply the sender
        }
        // else = don't do anything because we know the worker is already
        // doing stuff. MDC: I think this should never happen, but we have
        // this bookkeeping in case of failing workers, so why not use it.
      }

    /**
     *  Worker has completed its work and we can clear it out
     */
    case WorkerIsDoneAndRequestsWork(worker) =>
      if (!workers.contains(worker))
        log.error(s"Blurgh! $worker said it's done work but we didn't know about him")
      else
        workers += (worker -> None)
        // send the worker some work
        self ! WorkerRequestsWork(worker)

    /**
     *  A worker died.  If he was doing anything then we need
     *  to give it to someone else so we just add it back to the
     *  master and let things progress as usual
     */
    case Terminated(worker) =>
      if (workers.contains(worker) && workers(worker) != None) {
        log.error(s"Blurgh! $worker died while processing ${workers(worker)}")
        // Send the work that it was doing back to ourselves for processing
        val (workSender, work) = workers(worker).get
        self.tell(work, workSender)
      }
      workers -= worker

    /**
     *
     */
    case AuthorizationRequest(subjectId, actionId, resourceId) =>
      log.debug(s"Queueing ($subjectId, $actionId, $resourceId) from $sender")
      val id = clients.store(sender)
      workQ.enqueue(sender -> new PolicyEvaluationRequest(id, Top, subjectId, actionId, resourceId))
      notifyWorkers()

    /**
     *
     */
    case PolicyEvaluationResult(id, result) =>
      log.debug(s"Received authorization decision: ($id, $result)")
      val client = clients.get(id)
      // TODO: fulfill obligations here
      client ! AuthorizationDecision(result.decision)
      stats.tick

    /**
     * Unknown messages
     */
    case x => log.error(s"Unknown message received: $x")

  }
}