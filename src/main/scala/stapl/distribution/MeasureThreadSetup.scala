package stapl.distribution

object MeasureThreadSetup extends App {
    
  def duration(t0: Long, t1: Long) = (t1.toDouble - t0.toDouble) / 1000000.0
  
  val start = System.nanoTime()
  
  for(i <- 1 to 100) {
    new Thread(new Runnable() {
      def run() {
        println(duration(start, System.nanoTime()))
      }
    }).start()
  }

}