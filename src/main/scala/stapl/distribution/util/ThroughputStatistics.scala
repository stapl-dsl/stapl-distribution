package stapl.distribution.util

/**
 * Class used for keeping and printing throughput statistics.
 */
class ThroughputStatistics {
  val totalStart = System.nanoTime()
  var intervalStart = System.nanoTime()

  var totalCounter = 0L
  var intervalCounter = 0L
  
  def tick() = {
    totalCounter += 1
    intervalCounter += 1L
    printThroughput
  }

  def totalCount = totalCounter
  def intervalCount = intervalCounter

  /**
   * The duration in ms
   */
  def totalDuration = {
    val now = System.nanoTime()
    (now.toDouble - totalStart.toDouble) / 1000000.0
  }
  def intervalDuration = {
    val now = System.nanoTime()
    (now.toDouble - intervalStart.toDouble) / 1000000.0
  }
  
  /**
   * In requests/sec
   */
  def totalThroughput = totalCount.toDouble / (totalDuration/1000)
  def intervalThroughput = intervalCount.toDouble / (intervalDuration/1000)
  
  def resetInterval = {
    intervalStart = System.nanoTime()
    intervalCounter = 0
  }

  def printThroughput {
    if (intervalCount % 1000 == 0 && intervalCount > 1000 && intervalDuration > 1000) {
      println(f"Coordinator: total throughput = $totalThroughput%2.2f requests/sec, last interval throughput = $intervalThroughput%2.2f")
      resetInterval
    }
  }
}