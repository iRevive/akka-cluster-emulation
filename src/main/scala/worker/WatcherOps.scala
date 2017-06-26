package worker

import akka.actor.{Actor, ActorLogging, ActorRef}
import message.NewNode
import watcher.NodeWatcher
import watcher.NodeWatcher.WatcherMessage

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
  * @author Maksim Ochenashko
  */
private[worker] trait WatcherOps {
  _: Actor with ActorLogging =>

  protected final def getOrCreateWatcher(info: RemoteActorInfo): ActorRef =
    context.child(info.hexName) getOrElse {
      log info s"Resolving new node. Sender [${info.path}]"
      initWatcher(info)
    }

  protected final def initWatchers(addresses: Seq[String]): Unit =
    for {
      nodeAddress <- addresses
      info = RemoteActorInfo(nodeAddress)
      actor = getOrCreateWatcher(info)
    } actor ! WatcherMessage.ForwardToNode(NewNode)

  protected final def initWatcher(info: RemoteActorInfo): ActorRef = {
    val selection = context.actorSelection(info.path)
    context.actorOf(NodeWatcher.props(selection), name = info.hexName)
  }

  protected final def activeNodes(implicit ec: ExecutionContext): Future[List[ActorRef]] = {
    import akka.pattern._
    import scala.concurrent.duration._

    implicit val timeout = akka.util.Timeout(3.second)

    def reduce(accFuture: Future[List[ActorRef]], resolveFuture: Future[(NodeWatcher.NodeState, ActorRef)]) = {
      val mappedFuture = resolveFuture
        .map {
          case (NodeWatcher.NodeState.Connected, ref) => ref :: Nil
          case _ => Nil
        }
        .recover { case NonFatal(_) => Nil }

      for {
        acc <- accFuture
        f <- mappedFuture
      } yield acc ++ f
    }

    context.children
      .map { actor =>
        for {
          r <- (actor ? NodeWatcher.WatcherMessage.RequestState).mapTo[NodeWatcher.NodeState]
        } yield (r, actor)
      }
      .foldLeft(Future.successful(List.empty[ActorRef]))(reduce)
  }

  protected final def watchers(except: RemoteActorInfo): Iterable[ActorRef] =
    for {
      watcher <- context.children
      if watcher.path.name != except.hexName
    } yield watcher

  protected final def watcherExist(info: RemoteActorInfo): Boolean =
    context.child(info.hexName).nonEmpty

}
