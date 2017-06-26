import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import worker.Worker

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, FiniteDuration}

/**
  * @author Maksim Ochenashko
  */
object AppBootstrap {

  def main(args: Array[String]): Unit = {
    val ClusterConfig(addresses, interval) = loadConfig()

    val system = ActorSystem("system")

    val nodes = addresses map { case Node(host, port) =>
      s"akka.tcp://system@$host:$port/user/Worker"
    }

    system.log.info("Loaded nodes: {}", addresses)

    system.actorOf(Worker.props(nodes, interval), name = "Worker")

    Await.result(system.whenTerminated, Duration.Inf)
  }

  def loadConfig(): ClusterConfig = {
    import scala.collection.JavaConversions._

    val config = ConfigFactory.load()

    val cluster = config.getConfigList("cluster.nodes")

    val nodes = cluster.map { value => Node(value.getString("hostname"), value.getInt("port")) }.toList

    val messageInterval = config.getDuration("cluster.message-interval")

    ClusterConfig(nodes, FiniteDuration(messageInterval.toNanos, TimeUnit.NANOSECONDS))
  }

  case class Node(host: String, port: Int)

  case class ClusterConfig(nodes: List[Node], interval: FiniteDuration)

}

