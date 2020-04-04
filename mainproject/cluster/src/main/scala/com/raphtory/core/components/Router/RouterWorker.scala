package com.raphtory.core.components.Router

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator
import com.raphtory.core.model.communication.AllocateJob
import com.raphtory.core.model.communication.GraphUpdate
import com.raphtory.core.model.communication.UpdatedCounter
import com.raphtory.core.utils.Utils.getManager

import scala.util.hashing.MurmurHash3

trait RouterWorker extends Actor with ActorLogging {
  private var managerCount: Int = initialManagerCount

  protected def initialManagerCount: Int
  protected def parseTuple(value: Any)

  final protected val mediator = DistributedPubSub(context.system).mediator
  mediator ! DistributedPubSubMediator.Put(self)

  override def receive: Receive = {
    case req: UpdatedCounter => handleUpdatedCounterRequest(req)
    case req: AllocateJob    => handleAllocateJobRequest(req)
    case x                   => log.warning("RouterWorker received unknown message [{}]", x)
  }

  final protected def getManagerCount: Int = managerCount

  def assignID(uniqueChars: String): Long = MurmurHash3.stringHash(uniqueChars)

  def handleUpdatedCounterRequest(req: UpdatedCounter): Unit = {
    log.debug(s"RouterWorker received message [{}]", req)

    if (managerCount < req.newValue) managerCount = req.newValue
  }

  def handleAllocateJobRequest(req: AllocateJob): Unit = {
    log.debug(s"RouterWorker Received message [{}]", req)

    parseTuple(req.record)
  }

  def sendGraphUpdate[T <: GraphUpdate](message: T): Unit = {
    mediator ! DistributedPubSubMediator
      .Send(path = getManager(message.srcID, getManagerCount), msg = message, localAffinity = false)

    log.debug("RouterWorker sending message [{}] to PubSub", message)
  }
}
