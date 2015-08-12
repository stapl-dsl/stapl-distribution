/**
 *    Copyright 2015 KU Leuven Research and Developement - iMinds - Distrinet
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    Administrative Contact: dnet-project-office@cs.kuleuven.be
 *    Technical Contact: maarten.decat@cs.kuleuven.be
 *    Author: maarten.decat@cs.kuleuven.be
 */
package stapl.distribution.db.entities

import org.joda.time.LocalDateTime

class DateHelper {
  
  // month 4 = april
	
	def now() = new LocalDateTime(2013, 4, 10, 14, 18, 22)
	
	def normalShiftStart() = new LocalDateTime(2013, 4, 10, 8, 30, 00)
	
	def normalShiftStop() = new LocalDateTime(2013, 4, 10, 17, 00, 00)
	
	def earlyShiftStart() = new LocalDateTime(2013, 4, 9, 22, 00, 00)
	
	def earlyShiftStop() = normalShiftStart()
	
	def lateShiftStart() = normalShiftStop()
	
	def lateShiftStop() = new LocalDateTime(2013, 4, 10, 22, 00, 00)
	
	def yesterday() = daysAgo(1)
	
	def threeDaysAgo() = daysAgo(3)
	
	def fiveDaysAgo() = daysAgo(5)
	
	def twoWeeksAgo() = daysAgo(14)
	
	def daysAgo(amount: Int) = now.plusDays(-amount)
}