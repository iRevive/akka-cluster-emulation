package worker

import java.util.UUID

import akka.actor.{Actor, ActorLogging, Cancellable, PoisonPill, Props}
import message._
import watcher.NodeWatcher.WatcherMessage
import worker.Worker.WorkerMessage

import scala.concurrent.duration._

/**
  * @author Maksim Ochenashko
  */
class Worker(nodesAddresses: Seq[String], var messageInterval: FiniteDuration) extends Actor
  with ActorLogging
  with ContextOps
  with WatcherOps {

  private val CleanUpMultiplier = 2

  private var processedMessages: Map[UUID, Long] = Map.empty

  private val metric = new Metric

  private var (sendMessageTask, logStatTask, cleanUpTask) =
    (Option.empty[Cancellable], Option.empty[Cancellable], Option.empty[Cancellable])

  private var bestThroughput: Double = 0d

  override def receive: Receive = {
    case message: RemoteMessage =>
      remoteMessageHandler.applyOrElse[RemoteMessage, Unit](
        message,
        unhandled => log.warning(s"Unhandled message.RemoteMessage [$unhandled]")
      )

    case message: WorkerMessage =>
      workerMessageHandler.applyOrElse[WorkerMessage, Unit](
        message,
        unhandled => log.warning(s"Unhandled WorkerMessage [$unhandled]")
      )

  }

  private[this] val remoteMessageHandler: PartialFunction[RemoteMessage, Unit] = {
    case NewNode =>
      handleNewNode()

    case ActiveNodes(addresses) =>
      log info s"Active nodes: [$addresses]"
      initWatchers(addresses)

    case NodeTermination =>
      withSenderInfo { case RemoteActorInfo(path, hexName) =>
        log info s"Removing active node. Sender [$path]"
        context.child(hexName) foreach { actor => actor ! PoisonPill }
      }

    case msg@SetTimeout(uuid, timeout) =>
      withSenderInfo { case info@RemoteActorInfo(path, _) =>
        getOrCreateWatcher(info)

        if (!processedMessages.contains(uuid)) {
          log info s"Received new timeout [$timeout] value. Sender [$path]"
          messageInterval = timeout
          scheduleSendMessage(timeout)
          scheduleCleanUp(timeout * CleanUpMultiplier)
          processedMessages = processedMessages.updated(uuid, Metric.now)
          scheduleOnce(2.second, WorkerMessage.RecalculateThroughput)
          watchers(info) foreach { watcher => watcher forward WatcherMessage.ForwardToNode(msg) }
        }
      }

    case msg@NodeMessage(uuid) =>
      withSenderInfo { case info@RemoteActorInfo(path, _) =>
        getOrCreateWatcher(info)

        if (!processedMessages.contains(uuid)) {
          log debug s"NodeMessage received. Sender: [$path]"
          metric.inc(Metric.now)
          processedMessages = processedMessages.updated(uuid, Metric.now)
          watchers(info) foreach { watcher => watcher forward WatcherMessage.ForwardToNode(msg) }
        }
      }

    case LogStat =>
      val ts = Metric.secondBefore(Metric.now)
      val mps = metric.count(ts)
      log info s"Number of messages processed per second [$mps]"
      metric.cleanUp(Metric.secondBefore(ts))
  }

  private[this] val workerMessageHandler: PartialFunction[WorkerMessage, Unit] = {
    case WorkerMessage.SendMessage =>
      context.children foreach { actor => actor ! WatcherMessage.ForwardToNode(NodeMessage(UUID.randomUUID())) }

    case WorkerMessage.RequestLogStat =>
      context.children foreach { actor => actor ! WatcherMessage.ForwardToNode(LogStat) }

    case WorkerMessage.CleanUp =>
      val lowerBound = Metric.now - (messageInterval * CleanUpMultiplier).toMillis
      processedMessages = processedMessages filter { case (_, timestamp) => timestamp <= lowerBound }

    case WorkerMessage.RecalculateThroughput =>
      calculateThroughput()

    case WorkerMessage.Throughput(value) =>
      log info s"Best throughput [$bestThroughput]. Current [$value]"
      if (bestThroughput < value) bestThroughput = value
  }

  override def preStart(): Unit = {
    initWatchers(nodesAddresses)
    context.children foreach { actor => actor ! WatcherMessage.ForwardToNode(SetTimeout(UUID.randomUUID(), messageInterval)) }
    scheduleSendMessage(messageInterval)
    scheduleLogStat()
    scheduleCleanUp(messageInterval * CleanUpMultiplier)
  }

  override def postStop(): Unit =
    for {
      task <- List(sendMessageTask, logStatTask, cleanUpTask).flatten
      if !task.isCancelled
    } task.cancel()

  private def handleNewNode(): Unit =
    withSenderInfo {
      case info if !watcherExist(info) =>
        val watcher = getOrCreateWatcher(info)

        val addresses = for {
          watcher <- watchers(info)
        } yield HexStringUtil.hex2string(watcher.path.name)

        if (addresses.nonEmpty)
          watcher ! WatcherMessage.ForwardToNode(ActiveNodes(addresses.toList))

      case _ =>
    }

  private def scheduleSendMessage(timeout: FiniteDuration): Unit = {
    val task = schedule(timeout, WorkerMessage.SendMessage)

    sendMessageTask foreach { cancellable => cancellable.cancel() }
    sendMessageTask = Some(task)
  }

  private def scheduleLogStat(): Unit = {
    val task = schedule(10.second, WorkerMessage.RequestLogStat)

    logStatTask foreach { cancellable => cancellable.cancel() }
    logStatTask = Some(task)
  }

  private def scheduleCleanUp(timeout: FiniteDuration): Unit = {
    val task = schedule(timeout, WorkerMessage.CleanUp, timeout)

    cleanUpTask foreach { cancellable => cancellable.cancel() }
    cleanUpTask = Some(task)
  }

  private def calculateThroughput(): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    import akka.pattern.pipe

    val ts = Metric.secondBefore(Metric.now)
    val mps = metric.count(ts)

    (for {nodes <- activeNodes} yield WorkerMessage.Throughput((nodes.length.toDouble / 100) * mps)) pipeTo self
  }

}

object Worker {

  def props(nodes: Seq[String], messageInterval: FiniteDuration): Props =
    Props(classOf[Worker], nodes, messageInterval)

  sealed trait WorkerMessage

  object WorkerMessage {

    case object SendMessage extends WorkerMessage

    case object RequestLogStat extends WorkerMessage

    case object CleanUp extends WorkerMessage

    case object RecalculateThroughput extends WorkerMessage

    case class Throughput(value: Double) extends WorkerMessage

  }

}

