/*
 * Copyright 2026 Plees Tracker contributors
 *
 * SPDX-License-Identifier: MIT
 */
package hu.vmiklos.plees_tracker

import android.content.SharedPreferences
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class RestGoals(
    val hours: Float = 8f,
    val bedtime: Int = 22 * 60,
    val wakeup: Int = 7 * 60,
    val consistentNights: Int = 5,
) {
    fun save(preferences: SharedPreferences) {
        preferences.edit {
            putString(
                "ideal_sleep_length",
                (hours.takeIf { it.isFinite() } ?: 8f).coerceIn(2f, 14f).toString(),
            )
            putInt("goal_bedtime_minutes", bedtime.coerceIn(0, 1439))
            putInt("goal_wakeup_minutes", wakeup.coerceIn(0, 1439))
            putInt("goal_consistent_nights", consistentNights.coerceIn(1, 7))
        }
    }

    companion object {
        fun read(preferences: SharedPreferences) =
            RestGoals(
                (
                    preferences.getString("ideal_sleep_length", "8.0")?.toFloatOrNull()?.takeIf {
                        it.isFinite()
                    } ?: 8f
                    )
                    .coerceIn(2f, 14f),
                preferences.getInt("goal_bedtime_minutes", 22 * 60).coerceIn(0, 1439),
                preferences.getInt("goal_wakeup_minutes", 7 * 60).coerceIn(0, 1439),
                preferences.getInt("goal_consistent_nights", 5).coerceIn(1, 7),
            )
    }
}

data class RestDay(
    val date: Long,
    val sleeps: List<Sleep>,
    val stages: Map<EstimatedStage, Long>,
    val divisor: Int = 1,
    val labelPattern: String = "MMM d",
) {
    val duration: Long
        get() = sleeps.sumOf { (it.stop - it.start).coerceAtLeast(0) } / divisor.coerceAtLeast(1)

    val mainSleep: Sleep?
        get() = sleeps.maxByOrNull { it.stop - it.start }
}

/** Calendar-aware presentation math; stage estimates are never synthesized for missing records. */
object RestInsights {
    fun midnight(time: Long): Long =
        Calendar.getInstance()
            .apply {
                timeInMillis = time
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            .timeInMillis

    fun plusDays(time: Long, amount: Int): Long =
        Calendar.getInstance()
            .apply {
                timeInMillis = time
                add(Calendar.DATE, amount)
            }
            .timeInMillis

    fun minute(time: Long): Int =
        Calendar.getInstance()
            .apply { timeInMillis = time }
            .let {
                it[Calendar.HOUR_OF_DAY] * 60 + it[Calendar.MINUTE]
            }

    fun clockDifference(actual: Int, target: Int): Int = ((actual - target + 2160) % 1440) - 720

    fun onSchedule(sleep: Sleep?, goals: RestGoals): Boolean =
        sleep != null &&
            abs(clockDifference(minute(sleep.start), goals.bedtime)) <= 30 &&
            abs(clockDifference(minute(sleep.stop), goals.wakeup)) <= 30

    fun intervals(sleep: Sleep): List<StageInterval> =
        SleepStageEstimator.estimate(
            SensorNight.decode(sleep.sensorData),
            sleep.start,
            sleep.stop,
        )

    fun stages(sleep: Sleep): Map<EstimatedStage, Long> =
        intervals(sleep)
            .groupBy { it.stage }
            .mapValues { (_, values) -> values.sumOf { it.end - it.start } }

    fun days(sleeps: List<Sleep>, ending: Long, count: Int): List<RestDay> {
        val groups = sleeps.filter { it.stop > it.start }.groupBy { midnight(it.stop) }
        return (count - 1 downTo 0).map { back ->
            val day = plusDays(midnight(ending), -back)
            val records = groups[day].orEmpty()
            val totals = mutableMapOf<EstimatedStage, Long>()
            records.forEach { sleep ->
                stages(sleep).forEach { (stage, ms) ->
                    totals[stage] = (totals[stage] ?: 0L) + ms
                }
            }
            RestDay(day, records, totals)
        }
    }

    /** Month/year charts show daily averages over tracked days, not summed monthly hours. */
    fun aggregate(sleeps: List<Sleep>, ending: Long, yearly: Boolean, count: Int): List<RestDay> {
        fun bucket(time: Long): Long =
            Calendar.getInstance()
                .apply {
                    timeInMillis = midnight(time)
                    set(Calendar.DAY_OF_MONTH, 1)
                    if (yearly) set(Calendar.MONTH, Calendar.JANUARY)
                }
                .timeInMillis
        val groups = sleeps.filter { it.stop > it.start }.groupBy { bucket(it.stop) }
        val last = bucket(ending)
        return (count - 1 downTo 0).map { back ->
            val date =
                Calendar.getInstance()
                    .apply {
                        timeInMillis = last
                        add(if (yearly) Calendar.YEAR else Calendar.MONTH, -back)
                    }
                    .timeInMillis
            val records = groups[date].orEmpty()
            val divisor = records.map { midnight(it.stop) }.distinct().size.coerceAtLeast(1)
            val totals = mutableMapOf<EstimatedStage, Long>()
            records.forEach { sleep ->
                stages(sleep).forEach { (stage, ms) -> totals[stage] = (totals[stage] ?: 0L) + ms }
            }
            RestDay(
                date,
                records,
                totals.mapValues { it.value / divisor },
                divisor,
                if (yearly) "yyyy" else "MMM yy",
            )
        }
    }

    fun historyStart(duration: Int, now: Long): Long {
        if (duration == 0) return 0L
        if (duration == -365)
            return Calendar.getInstance()
                .apply {
                    timeInMillis = midnight(now)
                    set(Calendar.DAY_OF_MONTH, 1)
                    add(Calendar.MONTH, -11)
                }
                .timeInMillis
        return plusDays(midnight(now), duration + 1)
    }

    fun monthSpan(first: Long, last: Long): Int {
        val a = Calendar.getInstance().apply { timeInMillis = first }
        val b = Calendar.getInstance().apply { timeInMillis = last }
        return (
            (b[Calendar.YEAR] - a[Calendar.YEAR]) * 12 + b[Calendar.MONTH] - a[Calendar.MONTH] +
                1
            )
            .coerceAtLeast(1)
    }

    fun yearSpan(first: Long, last: Long): Int {
        val a = Calendar.getInstance().apply { timeInMillis = first }
        val b = Calendar.getInstance().apply { timeInMillis = last }
        return (b[Calendar.YEAR] - a[Calendar.YEAR] + 1).coerceAtLeast(1)
    }

    fun weeklySchedule(sleeps: List<Sleep>, ending: Long, goals: RestGoals): List<Boolean?> =
        days(sleeps, ending, 7).map { day -> day.mainSleep?.let { onSchedule(it, goals) } }

    fun estimatedCoverage(totals: Map<EstimatedStage, Long>): Int {
        val total = totals.values.sum()
        return if (total <= 0) 0
        else
            (((total - (totals[EstimatedStage.UNKNOWN] ?: 0L)) * 100) / total)
                .toInt()
                .coerceIn(0, 100)
    }

    fun duration(ms: Long): String {
        val minutes = ms.coerceAtLeast(0) / 60000
        if (minutes < 60) return "${minutes}m"
        return if (minutes % 60 == 0L) "${minutes / 60}h" else "${minutes / 60}h ${minutes % 60}m"
    }

    fun date(time: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(time))
}
