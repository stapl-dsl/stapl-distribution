package stapl.distribution.db.entities

import org.joda.time.LocalDateTime

class DateHelper {
  
  // month 4 = april
	
	def now() = new LocalDateTime(2013, 4, 2, 14, 18, 22)
	
	def normalShiftStart() = new LocalDateTime(2013, 4, 2, 8, 30, 00)
	
	def normalShiftStop() = new LocalDateTime(2013, 4, 2, 17, 00, 00)
	
	def earlyShiftStart() = new LocalDateTime(2013, 4, 1, 22, 00, 00)
	
	def earlyShiftStop() = normalShiftStart()
	
	def lateShiftStart() = normalShiftStop()
	
	def lateShiftStop() = new LocalDateTime(2013, 4, 2, 22, 00, 00)
	
	def yesterday() = daysAgo(1)
	
	def threeDaysAgo() = daysAgo(3)
	
	def fiveDaysAgo() = daysAgo(5)
	
	def twoWeeksAgo() = daysAgo(14)
	
	def daysAgo(amount: Int) = now.plusDays(-amount)
}