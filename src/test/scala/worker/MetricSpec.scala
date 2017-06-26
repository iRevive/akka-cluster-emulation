package worker

import org.scalatest.{FlatSpec, Matchers}

/**
  * @author Maksim Ochenashko
  */
class MetricSpec extends FlatSpec with Matchers {

  it should "correctly apply lower bound" in {
    val metric = initMetric(0, 3, 2, 5, 1, 4)

    metric.applyLowerBound(lowerBound = 2).clone().dequeueAll.toList shouldBe List(2, 3, 4, 5)
  }

  it should "correctly calculate count according to the timestamp" in {
    val metric = initMetric(0, 3, 2, 5, 1, 4, 2, 3, 1, 6, 0, 10, 0, 2, 1)

    metric.count(tsLowerBound = 2) shouldBe 9
  }

  it should "correctly clean up the underlying queue" in {
    val metric = initMetric(5, 4, 3, 2, 1)

    metric.cleanUp(tsLowerBound = 3)
    metric.applyLowerBound(0).clone().dequeueAll.toList shouldBe List(3, 4, 5)
  }

  def initMetric(args: Long*): Metric = {
    val metric = new Metric

    require(args.nonEmpty, "Args could not be empty")

    for (arg <- args) metric.inc(arg)

    metric
  }
}
