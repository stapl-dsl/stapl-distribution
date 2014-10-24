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

case class CoordinatorConfig(hostname: String = "not-provided", port: Int = -1)

object CoordinatorApp {

  def main(args: Array[String]) {
    val parser = new scopt.OptionParser[CoordinatorConfig]("scopt") {
      head("STAPL - coordinator")
      opt[String]('h', "hostname") required() action { (x, c) =>
        c.copy(hostname = x)
      } text ("The hostname of the machine on which this coordinator is run. This hostname will be used by other actors in their callbacks, so it should be externally accessible if you deploy the components on different machines.")

      opt[Int]('p', "port") required() action { (x, c) =>
        c.copy(port = x)
      } text ("The port on which this coordinator will be listening.")
      help("help") text ("prints this usage text")
    }
    // parser.parse returns Option[C]
    parser.parse(args, CoordinatorConfig()) map { config =>
      val defaultConf = ConfigFactory.load()
      val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = ${config.hostname}
        akka.remote.netty.tcp.port = ${config.port}
      """).withFallback(defaultConf)
      val system = ActorSystem("STAPL-coordinator", customConf)

      val coordinator = system.actorOf(Props[Coordinator], "coordinator")

      println(s"Coordinator up and running at ${config.hostname}:${config.port}")
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}