package stapl.distribution.util

import stapl.distribution.components.ClientCoordinatorProtocol
import stapl.distribution.db.entities.ehealth.EhealthEntityManager
import scala.util.Random
import stapl.distribution.db.entities.ArtificialEntityManager

trait RequestGenerator {
  
  def nextRequest(): ClientCoordinatorProtocol.AuthorizationRequest
}

class Ehealth29RequestsGenerator extends RequestGenerator {
  
  val em = EhealthEntityManager()
  
  override def nextRequest() = { 
    val requests = em.requests.keySet.toVector
    val (subject,action,resource,extraAttributes) = requests(Random.nextInt(requests.size))
    new ClientCoordinatorProtocol.AuthorizationRequest(subject.id, action, resource.id, extraAttributes)
  }  
}

class EhealthRandomRequestGenerator extends RequestGenerator {
  
  val em = EhealthEntityManager()
  
  override def nextRequest() = { 
    val requests = em.requests.keySet.toVector
    val (subject,action,resource,extraAttributes) = requests(Random.nextInt(requests.size))
    new ClientCoordinatorProtocol.AuthorizationRequest(em.randomSubject.id, "view", em.randomResource.id)
  }    
}

class RandomArtificialRequestGenerator(nbArtificialSubjects: Int = 1000, nbArtificialResources: Int = 1000) extends RequestGenerator {
  
  val em = ArtificialEntityManager(nbArtificialSubjects, nbArtificialResources)
  
  override def nextRequest() = new ClientCoordinatorProtocol.AuthorizationRequest(em.randomSubject.id, "view", em.randomResource.id)  
}