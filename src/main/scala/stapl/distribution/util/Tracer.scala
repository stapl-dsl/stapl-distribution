package stapl.distribution.util

/**
 *
 */
case class TraceStep(val component: String, val label: String, val start: Long, val stop: Long)

/**
 *
 */
class Trace(val evaluationId: String) {

  val steps = scala.collection.mutable.ListBuffer[TraceStep]()

  def +=(step: TraceStep) = steps += step

  def merge(other: Trace) {
    if (!(other.evaluationId == evaluationId)) {
      throw new IllegalArgumentException(s"The evaluation ids do not match. This: $evaluationId. Given: ${other.evaluationId}")
    }
    steps ++= other.steps
  }

  /**
   * Helper function to return the difference between two
   * System.nanoTime() timestamps (Longs) in ms.
   */
  private def duration(t0: Long, t1: Long) = (t1 - t0).toDouble / 1000000.0

  def prettyPrint() = {
    // construct the different start and stop steps
    val details = scala.collection.mutable.Map[Long, String]()
    for (step <- steps) {
      details(step.start) = s"${step.component}:${step.label} (start)"
      details(step.stop) = s"${step.component}:${step.label} (stop)"
    }
    // print the different starts and stops in order
    println("Trace of evaluation $evalautionId")
    println("===========================================")
    val sortedTimestamps = details.keys.toList.sorted
    val startTime = sortedTimestamps(0)
    var lastNanos = -1L
    for (nanos <- sortedTimestamps) {
      var diff = "-"
      if (lastNanos != -1L) {
        println(f"  |-> ${duration(lastNanos, nanos)}%2.2f ms")
      }
      println(f"${duration(startTime, nanos)}%2.2f ms - ${details(nanos)}")
      lastNanos = nanos
    }
  }
}

/**
 * Helper class for constructing traces of policy evaluations.
 */
object Tracer {

  private[util] var enabled = false

  def enable() = {
    enabled = true
  }

  def disable() = {
    enabled = false
  }

  /**
   * evaluationId -> Trace
   */
  val traces = scala.collection.mutable.Map[String, Trace]()

  def start(evaluationId: String, component: String, label: String) = {
    new TraceClosure(evaluationId, component, label)
  }

  def trace[R](evaluationId: String, component: String, label: String)(block: => R): R = {
    val closure = start(evaluationId, component, label)
    val result = block // call-by-name
    closure.stop
    result
  }

  def getTraceOfEvaluation(evaluationId: String) = traces(evaluationId)
}

/**
 *
 */
class TraceClosure(val evaluationId: String, val component: String, val label: String) {

  import Tracer._

  val start = now()

  def now() = System.nanoTime()

  def stop() = {
    if (enabled) {
      if (!traces.contains(evaluationId)) {
        traces(evaluationId) = new Trace(evaluationId)
      }
      traces(evaluationId) += TraceStep(component, label, start, now())
    }
  }
}