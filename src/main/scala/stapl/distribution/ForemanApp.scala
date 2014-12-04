package stapl.distribution

import akka.actor.ActorRef
import stapl.core.Permit
import stapl.core.Result
import akka.actor.ActorSystem
import scala.concurrent.duration._
import scala.util.{ Success, Failure }
import akka.actor.Props
import akka.actor.actorRef2Scala
import scala.math.BigInt.int2bigInt
import stapl.distribution.components.Foreman
import com.typesafe.config.ConfigFactory
import stapl.examples.policies.EhealthPolicy
import stapl.core.AbstractPolicy
import stapl.distribution.policies.ConcurrencyPolicies
import stapl.distribution.components.ClientCoordinatorProtocol
import com.hazelcast.config.Config
import stapl.distribution.db.AttributeMapStore
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.IMap
import stapl.core.AttributeContainerType
import stapl.distribution.db.HazelcastAttributeDatabaseConnection
import com.hazelcast.config.MapConfig
import com.hazelcast.config.MapStoreConfig

case class ForemanConfig(name: String = "not-provided",
  hostname: String = "not-provided", port: Int = -1,
  coordinatorHostname: String = "not-provided", coordinatorPort: Int = -1,
  nbWorkers: Int = -1, policy: String = "not-provided")

object ForemanApp {
  def main(args: Array[String]) {

    val policies = Map(
      "ehealth" -> EhealthPolicy.naturalPolicy,
      "chinese-wall" -> ConcurrencyPolicies.chineseWall,
      "count" -> ConcurrencyPolicies.maxNbAccess)

    val parser = new scopt.OptionParser[ForemanConfig]("scopt") {
      head("STAPL - coordinator")
      opt[String]("name") required () action { (x, c) =>
        c.copy(name = x)
      } text ("The name of this foreman. This is used for debugging.")
      opt[String]("hostname") required () action { (x, c) =>
        c.copy(hostname = x)
      } text ("The hostname of the machine on which this foreman is run. This hostname will be used by other actors in their callbacks, so it should be externally accessible if you deploy the components on different machines.")
      opt[Int]("port") required () action { (x, c) =>
        c.copy(port = x)
      } text ("The port on which this foreman will be listening. 0 for a random port")
      opt[String]("coordinator-hostname") required () action { (x, c) =>
        c.copy(coordinatorHostname = x)
      } text ("The hostname of the machine on which the coordinator is running.")
      opt[Int]("coordinator-port") required () action { (x, c) =>
        c.copy(coordinatorPort = x)
      } text ("The port on which the coordinator is listening.")
      opt[Int]("nb-workers") required () action { (x, c) =>
        c.copy(nbWorkers = x)
      } text ("The number of workers to spawn for this foreman.")
      opt[String]("policy") required () action { (x, c) =>
        c.copy(policy = x)
      } validate { x =>
        if (policies.contains(x)) success else failure(s"Invalid policy given. Possible values: ${policies.keys}")
      } text (s"The policy to load in the PDPs. Valid values: ${policies.keys}")
      help("help") text ("prints this usage text")
    }
    // parser.parse returns Option[C]
    parser.parse(args, ForemanConfig()) map { config =>
      val defaultConf = ConfigFactory.load()
      val customConf = ConfigFactory.parseString(s"""
        akka.remote.netty.tcp.hostname = ${config.hostname}
        akka.remote.netty.tcp.port = ${config.port}
      """).withFallback(defaultConf)
      val system = ActorSystem("Foreman", customConf)

      // set up hazelcast db connection
      val MAP_NAME = "stapl-attributes"
      val cfg = new Config();
      cfg.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
      cfg.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true);
      cfg.getNetworkConfig().getJoin().getTcpIpConfig().addMember("127.0.0.1");
      val hazelcast = Hazelcast.newHazelcastInstance(cfg);

      val selection =
        system.actorSelection(s"akka.tcp://STAPL-coordinator@${config.coordinatorHostname}:${config.coordinatorPort}/user/coordinator")
      implicit val dispatcher = system.dispatcher
      selection.resolveOne(3.seconds).onComplete {
        case Success(coordinator) =>
          val foreman = system.actorOf(Props(classOf[Foreman], coordinator, config.nbWorkers, policies(config.policy), 
              new HazelcastAttributeDatabaseConnection(hazelcast.getMap(MAP_NAME))), "foreman")
          println(s"Forman ${config.name} up and running at ${config.hostname}:${config.port} with ${config.nbWorkers} workers")
        case Failure(t) =>
          t.printStackTrace()
          system.shutdown
      }
    } getOrElse {
      // arguments are bad, error message will have been displayed
    }
  }
}