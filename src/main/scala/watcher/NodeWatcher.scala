package watcher

import akka.actor.{ActorRef, ActorSelection, FSM, Props, Terminated, UnrestrictedStash}
import akka.remote.{DisassociatedEvent, RemotingLifecycleEvent}
import message.RemoteMessage
import watcher.NodeWatcher.WatcherMessage._
import watcher.NodeWatcher.{NodeState, WatcherData}

import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
  * @author Maksim Ochenashko
  */
class NodeWatcher(remoteActorPath: ActorSelection) extends FSM[NodeState, WatcherData] with UnrestrictedStash {

  private val MaxReconnectionAttemptCount = 10
  private val ReconnectionTimeout = 5.second

  startWith(NodeState.Disconnected, WatcherData.Empty)

  when(NodeState.Connected) {
    case Event(ForwardToNode(message), WatcherData.ConnectedData(remoteActor)) =>
      log debug s"Forwarding message [$message] to [$remoteActor]"
      remoteActor forward message
      stay()

    case Event(Terminated(actor), WatcherData.ConnectedData(remoteActor)) if actor == remoteActor =>
      log info s"Remote node [$remoteActor] terminated"
      goto(NodeState.Reconnection) using WatcherData.ReconnectionAttempt(0)

    case Event(DisassociatedEvent(_, remoteAddress, _), WatcherData.ConnectedData(remoteActor)) if remoteActor.path.address == remoteAddress =>
      log info s"Remote node [$remoteAddress] terminated"
      goto(NodeState.Reconnection) using WatcherData.ReconnectionAttempt(0)

    case Event(RequestState, _) =>
      sender() ! NodeState.Connected
      stay()

    case Event(_: RemotingLifecycleEvent, _) =>
      stay()
  }

  when(NodeState.Disconnected) {
    case Event(ResolvedRemoteActor(actor), _) =>
      log info s"New node [$actor] successfully resolved"
      goto(NodeState.Connected) using WatcherData.ConnectedData(actor)

    case Event(RemoteActorResolveError(cause), _) =>
      log error s"Remote actor resolve error: ${cause.getMessage}"
      goto(NodeState.Reconnection) using WatcherData.ReconnectionAttempt(0)

    case Event(_: ForwardToNode, _) =>
      stash()
      stay()

    case Event(RequestState, _) =>
      sender() ! NodeState.Disconnected
      stay()

    case Event(_: RemotingLifecycleEvent, _) =>
      stash()
      stay()
  }

  when(NodeState.Reconnection) {
    case Event(AttemptReconnect, WatcherData.ReconnectionAttempt(count)) =>
      log info s"Trying resolve node. Attempt #$count"
      resolveRemoteActor()
      stay() using WatcherData.ReconnectionAttempt(count + 1)

    case Event(ResolvedRemoteActor(actor), _)  =>
      log info s"New node [$actor] successfully resolved after reconnection"
      goto(NodeState.Connected) using WatcherData.ConnectedData(actor)

    case Event(RemoteActorResolveError(cause), WatcherData.ReconnectionAttempt(count)) if count >= MaxReconnectionAttemptCount =>
      log error s"Node resolve failed [$count] times. Cause: ${cause.getMessage}"
      stop()

    case Event(RemoteActorResolveError(cause), _) =>
      log error s"Remote actor resolve error: ${cause.getMessage}. Scheduling reconnection attempt."
      scheduleReconnect()
      stay()

    case Event(RequestState, _) =>
      sender() ! NodeState.Reconnection
      stay()

    case Event(_: ForwardToNode, _) =>
      stay()

    case Event(_: RemotingLifecycleEvent, _) =>
      stay()
  }

  onTransition {
    case _ -> NodeState.Reconnection =>
      scheduleReconnect()

    case _ -> NodeState.Connected =>
      watchActor()
      unstashAll()
  }

  initialize()

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[RemotingLifecycleEvent])
    resolveRemoteActor()
  }

  override def postStop(): Unit = {
    context.system.eventStream.unsubscribe(self)
    super.postStop()
  }

  private def resolveRemoteActor(): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    import akka.pattern.pipe

    implicit val t: akka.util.Timeout = akka.util.Timeout(5.second)

    remoteActorPath.resolveOne()
      .map { actor => ResolvedRemoteActor(actor) }
      .recover { case NonFatal(e) => RemoteActorResolveError(e) } pipeTo self
  }

  private def scheduleReconnect(): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    context.system.scheduler.scheduleOnce(ReconnectionTimeout, self, AttemptReconnect)
  }

  private def watchActor(): Unit =
    stateData match {
      case WatcherData.ConnectedData(actor) => context.watch(actor)
      case _ =>
    }
}

object NodeWatcher {

  def props(remoteActorPath: ActorSelection): Props = Props(classOf[NodeWatcher], remoteActorPath)

  sealed trait NodeState

  object NodeState {

    case object Connected extends NodeState

    case object Disconnected extends NodeState

    case object Reconnection extends NodeState

  }

  sealed trait WatcherData

  object WatcherData {

    case object Empty extends WatcherData

    case class ConnectedData(actor: ActorRef) extends WatcherData

    case class ReconnectionAttempt(count: Int) extends WatcherData

  }

  sealed trait WatcherMessage

  object WatcherMessage {

    case class ResolvedRemoteActor(actor: ActorRef) extends WatcherMessage

    case class RemoteActorResolveError(cause: Throwable) extends WatcherMessage

    case class ForwardToNode(message: RemoteMessage) extends WatcherMessage

    case object AttemptReconnect extends WatcherMessage

    case object RequestState extends WatcherMessage

  }

}