package stapl.distribution.test.actors.test

import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.ActorPath
import akka.actor.ActorRef

object Test extends App {

  val system = ActorSystem("Barista")

  def worker(masterName: String) = system.actorOf(Props(classOf[TestWorker], ActorPath.fromString(
      s"akka://${system.name}/user/$masterName")))
      
  def client(name: String, masterName: String) = system.actorOf(Props(classOf[Client], name, 
      ActorPath.fromString(s"akka://${system.name}/user/$masterName")))

  val clients = scala.collection.mutable.ArrayBuffer.empty[ActorRef]
  val workers = scala.collection.mutable.ArrayBuffer.empty[ActorRef]
  
  // Spin up the master
  val m = system.actorOf(Props[Master], "master")
  // Spin up multiple clients  
  for(i <- 1 to 20) {
    clients += client(s"$i", "master")
  }
  // Create multiple workers
  for(i <- 1 to 5) {
    workers += worker("master")
  }
  
  // launch the clients
  import ClientMasterProtocol._
  clients.foreach { x =>
    x ! Go
  }
  
  // TODO: add wait
  // system.shutdown
}