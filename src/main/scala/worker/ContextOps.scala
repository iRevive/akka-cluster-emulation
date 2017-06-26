package worker

import akka.actor.{Actor, Cancellable}

import scala.concurrent.duration.{Duration, FiniteDuration}

/**
  * @author Maksim Ochenashko
  */
private[worker] trait ContextOps {
  _: Actor =>

  protected final def senderInfo: RemoteActorInfo =
    RemoteActorInfo(context.sender().path.toString)

  protected final def withSenderInfo(f: RemoteActorInfo => Unit): Unit =
    f(senderInfo)

  protected final def schedule(timeout: FiniteDuration, msg: Any, delay: FiniteDuration = Duration.Zero): Cancellable = {
    import scala.concurrent.ExecutionContext.Implicits.global
    context.system.scheduler.schedule(delay, timeout, self, msg)
  }

  protected final def scheduleOnce(delay: FiniteDuration, msg: Any): Cancellable = {
    import scala.concurrent.ExecutionContext.Implicits.global
    context.system.scheduler.scheduleOnce(delay, self, msg)
  }

}