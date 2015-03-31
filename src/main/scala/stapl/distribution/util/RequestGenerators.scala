package stapl.distribution.util

import stapl.distribution.components.ClientCoordinatorProtocol
import stapl.distribution.db.entities.ehealth.EhealthEntityManager
import scala.util.Random

trait RequestGenerator {
  
  def nextRequest(): ClientCoordinatorProtocol.AuthorizationRequest
}

object Ehealth29RequestsGenerator extends RequestGenerator {
  
  val em = EhealthEntityManager()
  
  override def nextRequest() = { 
    val requests = em.requests.keySet.toVector
    val (subject,action,resource,extraAttributes) = requests(Random.nextInt(requests.size))
    new ClientCoordinatorProtocol.AuthorizationRequest(subject.id, action, resource.id, extraAttributes)
  }  
}

object EhealthRandomRequestGenerator extends RequestGenerator {
  
  val em = EhealthEntityManager()
  
  override def nextRequest() = { 
    val requests = em.requests.keySet.toVector
    val (subject,action,resource,extraAttributes) = requests(Random.nextInt(requests.size))
    new ClientCoordinatorProtocol.AuthorizationRequest(em.randomSubject.id, "view", em.randomResource.id)
  }    
}