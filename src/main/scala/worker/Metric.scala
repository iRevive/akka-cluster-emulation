package worker

import scala.collection.mutable

/**
  * @author Maksim Ochenashko
  */
private[worker] final class Metric {

  private[this] val sortingOrder = Ordering.Long.reverse
  private[this] var queue = mutable.PriorityQueue.empty[Long](Ordering.Long.reverse)

  def inc(timestamp: Long): Unit =
    queue += timestamp

  def count(tsLowerBound: Long): Long =
    applyLowerBound(tsLowerBound).length

  def cleanUp(tsLowerBound: Long): Unit =
    queue = applyLowerBound(tsLowerBound)

  def applyLowerBound(lowerBound: Long): mutable.PriorityQueue[Long] =
    queue.clone()
      .dequeueAll(mutable.PriorityQueue.canBuildFrom(sortingOrder))
      .dropWhile(_ < lowerBound)

}

private[worker] object Metric {

  def secondBefore(timestamp: Long): Long =
    timestamp - 1000

  def now: Long = System.currentTimeMillis()

}
