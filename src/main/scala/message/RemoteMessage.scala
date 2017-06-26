package message

import java.util.UUID

import scala.concurrent.duration.FiniteDuration

/**
  * @author Maksim Ochenashko
  */
trait RemoteMessage

case object NewNode extends RemoteMessage

case object NodeTermination extends RemoteMessage

case class SetTimeout(uuid: UUID, timeout: FiniteDuration) extends RemoteMessage

case class ActiveNodes(nodes: List[String]) extends RemoteMessage

case class NodeMessage(uuid: UUID) extends RemoteMessage

case object LogStat extends RemoteMessage