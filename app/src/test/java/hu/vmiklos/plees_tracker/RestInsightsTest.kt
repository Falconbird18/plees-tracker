/*
 * Copyright 2026 Plees Tracker contributors
 *
 * SPDX-License-Identifier: MIT
 */
package hu.vmiklos.plees_tracker

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestInsightsTest {
    private fun time(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance()
            .apply {
                clear()
                set(2026, Calendar.NOVEMBER, day, hour, minute)
            }
            .timeInMillis

    private fun sleep(start: Long, stop: Long) =
        Sleep().apply {
            this.start = start
            this.stop = stop
        }

    @Test
    fun historyRangesMatchTheirCalendarCharts() {
        val now = time(5, 14)
        assertEquals(
            RestInsights.plusDays(RestInsights.midnight(now), -6),
            RestInsights.historyStart(-7, now),
        )
        assertEquals(
            RestInsights.plusDays(RestInsights.midnight(now), -29),
            RestInsights.historyStart(-30, now),
        )
        assertEquals(0L, RestInsights.historyStart(0, now))
        val first =
            Calendar.getInstance().apply { timeInMillis = RestInsights.historyStart(-365, now) }
        assertEquals(1, first[Calendar.DAY_OF_MONTH])
        assertEquals(0, first[Calendar.HOUR_OF_DAY])
    }

    @Test
    fun shortRestsUseMinutesWithoutAnEmptyHourPrefix() {
        assertEquals("45m", RestInsights.duration(45 * 60000L))
        assertEquals("0m", RestInsights.duration(0))
    }

    @Test
    fun stageCoverageDoesNotTreatAnAbsentUnknownEntryAsMissingData() {
        assertEquals(100, RestInsights.estimatedCoverage(mapOf(EstimatedStage.LIGHT to 100L)))
        assertEquals(
            50,
            RestInsights.estimatedCoverage(
                mapOf(EstimatedStage.LIGHT to 100L, EstimatedStage.UNKNOWN to 100L)
            ),
        )
        assertEquals(0, RestInsights.estimatedCoverage(mapOf(EstimatedStage.UNKNOWN to 100L)))
        assertEquals(0, RestInsights.estimatedCoverage(emptyMap()))
    }

    @Test
    fun monthlyChartsUseDailyAveragesAndPreserveMissingMonths() {
        val end = time(5, 20)
        val rows = listOf(sleep(time(3, 22), time(4, 6)), sleep(time(4, 22), time(5, 4)))
        val months = RestInsights.aggregate(rows, end, false, 3)
        assertTrue(months.take(2).all { it.sleeps.isEmpty() })
        assertEquals(7 * 3600000L, months.last().duration)
        assertEquals(months.last().duration, months.last().stages.values.sum())
        assertEquals("MMM yy", months.last().labelPattern)
    }

    @Test
    fun scheduleDistanceWrapsAcrossMidnight() {
        assertEquals(-10, RestInsights.clockDifference(23 * 60 + 55, 5))
        assertEquals(10, RestInsights.clockDifference(5, 23 * 60 + 55))
        assertTrue(
            RestInsights.onSchedule(
                sleep(time(4, 23, 55), time(5, 7, 5)),
                RestGoals(bedtime = 5, wakeup = 7 * 60),
            )
        )
    }

    @Test
    fun emptyDaysRemainEmptyAndMultipleRestsAreSummed() {
        val records = listOf(sleep(time(4, 22), time(5, 6)), sleep(time(5, 14), time(5, 15)))
        val days = RestInsights.days(records, time(5, 20), 7)
        assertEquals(7, days.size)
        assertTrue(days.take(6).all { it.sleeps.isEmpty() })
        assertEquals(9 * 3600000L, days.last().duration)
        assertEquals(9 * 3600000L, days.last().stages[EstimatedStage.UNKNOWN])
        assertFalse(days.last().stages.keys.any { it != EstimatedStage.UNKNOWN })
    }

    @Test
    fun rhythmUsesTheLongestRestAndDoesNotCountMissingDays() {
        val goals = RestGoals(bedtime = 22 * 60, wakeup = 6 * 60)
        val records = listOf(sleep(time(4, 22), time(5, 6)), sleep(time(5, 14), time(5, 15)))
        val week = RestInsights.weeklySchedule(records, time(5, 20), goals)
        assertEquals(1, week.count { it == true })
        assertEquals(6, week.count { it == null })
    }

    @Test
    fun calendarBucketsSurviveDaylightSavingChanges() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val end =
                Calendar.getInstance()
                    .apply {
                        clear()
                        set(2026, Calendar.MARCH, 10, 12, 0)
                    }
                    .timeInMillis
            val dates = RestInsights.days(emptyList(), end, 7).map { it.date }
            assertEquals(7, dates.distinct().size)
            assertTrue(dates.zipWithNext().any { (a, b) -> b - a == 23 * 3600000L })
            assertTrue(dates.all { RestInsights.minute(it) == 0 })
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
