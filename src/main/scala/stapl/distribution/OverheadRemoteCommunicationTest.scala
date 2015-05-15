package stapl.distribution

import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.pattern.ask
import akka.util.Timeout
import stapl.distribution.db.entities.EntityManager
import stapl.distribution.db.entities.ehealth.EhealthEntityManager
import stapl.distribution.components.ClientCoordinatorProtocol
import stapl.distribution.components.CoordinatorForemanProtocol
import stapl.core.Deny
import stapl.distribution.util.Timer
import stapl.core.Result
import stapl.core.Deny
import stapl.examples.policies.EhealthPolicy

case class OverheadRemoteCommunicationTestSenderConfig(IP: String = "not-provided", receiverIP: String = "not-provided",
  receiverPort: Int = -1, nbWarmups: Int = -1, nbRuns: Int = -1)

case class OverheadRemoteCommunicationTestReceiverConfig(IP: String = "not-provided", port: Int = -1)

object OverheadRemoteCommunicationTestSenderApp {

  def main(args: Array[String]) {

    val parser = new scopt.OptionParser[OverheadRemoteCommunicationTestSenderConfig]("scopt") {
      head("A simple test to measure the overhead of remote communication")

      opt[String]("ip") required () action { (x, c) =>
        c.copy(IP = x)
      } text ("The IP address of this machine.")

      opt[String]("receiver-ip") required () action { (x, c) =>
        c.copy(receiverIP = x)
      } text ("The IP address of the machine on which the receiver is running.")

      opt[Int]("receiver-port") required () action { (x, c) =>
        c.copy(receiverPort = x)
      } text ("The port on which the receiver is listening.")

      opt[Int]("nb-warmup-runs") required () action { (x, c) =>
        c.copy(nbWarmups = x)
      } text ("The number of warmup runs to do for each request.")

      opt[Int]("nb-runs") required () action { (x, c) =>
        c.copy(nbRuns = x)
      } text ("The number of runs to do for each request.")

      help("help") text ("prints this usage text")
    }
    // parser.parse returns Option[C]
    parser.parse(args, OverheadRemoteCommunicationTestSenderConfig()) map { config =>
      val defaultConf = ConfigFactory.load()
      val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = ${config.IP}
        akka.remote.netty.tcp.port = 0
      """).withFallback(defaultConf)
      val system = ActorSystem("sender", customConf)

      val selection = system.actorSelection(s"akka.tcp://receiver@${config.receiverIP}:${config.receiverPort}/user/receiver")
      implicit val dispatcher = system.dispatcher
      implicit val timeout = Timeout(5.second)
      val receiver = Await.result(selection.resolveOne(3.seconds), 5.seconds)

      val em = EhealthEntityManager()

      val timer = new Timer("remoting-overhead")

      println(s"Doing ${config.nbWarmups} warmup requests and ${config.nbRuns} requests to receiver at ${config.receiverIP}:${config.receiverPort}")

      // warmup
      println("Warmup")
      for (i <- 1 to config.nbWarmups) {
        Await.result(receiver ? System.nanoTime(), 5.seconds) match {
          case msg => // nothing to do
        }
      }

      // the test
      println("Tests")
      val totals, steps1, steps2, steps3 = ListBuffer[Double]()
      for (i <- 1 to config.nbRuns) {
        if (i % 1000 == 0) {
          println(s"Run $i/${config.nbRuns}")
        }
        val request = em.randomRequest
        val authorizationRequest = ClientCoordinatorProtocol.AuthorizationRequest(request.subjectId, request.actionId, request.resourceId, request.extraAttributes)
        timer.start
        Await.result(receiver ? authorizationRequest, 5.seconds) match {
          case answer =>
            timer.stop
        }
      }

      println("#!Results")
      println(timer.toJSON())
      system.shutdown

    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}

object OverheadRemoteCommunicationTestReceiverApp {

  def main(args: Array[String]) {

    val parser = new scopt.OptionParser[OverheadRemoteCommunicationTestReceiverConfig]("scopt") {
      head("A simple test to measure the overhead of remote communication")

      opt[String]("ip") required () action { (x, c) =>
        c.copy(IP = x)
      } text ("The IP address of this machine.")

      opt[Int]("port") required () action { (x, c) =>
        c.copy(port = x)
      } text ("The port on to listening.")

      help("help") text ("prints this usage text")
    }
    // parser.parse returns Option[C]
    parser.parse(args, OverheadRemoteCommunicationTestReceiverConfig()) map { config =>
      val defaultConf = ConfigFactory.load()
      val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = ${config.IP}
        akka.remote.netty.tcp.port = ${config.port}
      """).withFallback(defaultConf)
      val system = ActorSystem("receiver", customConf)

      system.actorOf(Props(classOf[OverheadRemoteCommunicationTestReceiverActor]), "receiver")

      println(s"Listening on ${config.IP}:${config.port}")
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}
class OverheadRemoteCommunicationTestReceiverActor extends Actor {
  import EhealthPolicy._

  def receive = {
    case msg =>
      val result = new Result(Deny, List.empty, Map(
        subject.roles -> List("medical_personnel", "physician"),
        subject.triggered_breaking_glass -> true,
        subject.department -> "elder_care",
        resource.type_ -> "patientstatus",
        resource.owner_withdrawn_consents -> List("subject1", "subject2", "subject3", "maarten")))
      sender ! CoordinatorForemanProtocol.PolicyEvaluationResult("evaluationId", result)
  }
}








