/*
 * Copyright 2026 Plees Tracker contributors
 *
 * SPDX-License-Identifier: MIT
 */
package hu.vmiklos.plees_tracker

import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

/** Aggregated activity only: neither raw audio nor raw accelerometer samples are retained. */
data class SensorEpoch(
    val start: Long,
    val end: Long,
    val mean: Double,
    val variance: Double,
    val samples: Int,
    val interactive: Boolean,
)

enum class EstimatedStage {
    UNKNOWN,
    AWAKE,
    LIGHT,
    DEEP,
    REM,
}

data class StageInterval(val start: Long, val end: Long, val stage: EstimatedStage)

data class SensorNight(val mode: String, val epochs: List<SensorEpoch>, val issue: String = "") {
    fun encode(): String =
        JSONObject()
            .put("version", 1)
            .put("mode", mode)
            .put("issue", issue)
            .put(
                "epochs",
                JSONArray().also { rows ->
                    epochs.forEach { e ->
                        rows.put(
                            JSONArray()
                                .put(e.start)
                                .put(e.end)
                                .put(e.mean)
                                .put(e.variance)
                                .put(e.samples)
                                .put(e.interactive)
                        )
                    }
                },
            )
            .toString()

    companion object {
        fun decode(value: String): SensorNight? =
            try {
                val json = JSONObject(value)
                require(json.getInt("version") == 1)
                val rows = json.getJSONArray("epochs")
                require(rows.length() <= 5760)
                val epochs =
                    (0 until rows.length()).map { index ->
                        val row = rows.getJSONArray(index)
                        SensorEpoch(
                            row.getLong(0),
                            row.getLong(1),
                            row.getDouble(2),
                            row.getDouble(3),
                            row.getInt(4),
                            row.getBoolean(5),
                        )
                            .also {
                                require(it.end > it.start && it.end - it.start <= 31000)
                                require(
                                    it.mean.isFinite() &&
                                        it.variance.isFinite() &&
                                        it.mean >= 0 &&
                                        it.variance >= 0
                                )
                                require(it.samples >= 0)
                            }
                    }
                SensorNight(
                    json.getString("mode"),
                    epochs.sortedBy { it.start },
                    json.optString("issue"),
                )
            } catch (_: Exception) {
                null
            }
    }
}

/**
 * Experimental activity heuristics, independently implemented. No clinical validation is claimed.
 * Deep/REM labels combine activity with broad time-of-night priors; they are not EEG measurements.
 * Missing/short recordings stay unknown. A stage is never inferred from elapsed time alone.
 */
object SleepStageEstimator {
    fun estimate(night: SensorNight?, start: Long, stop: Long): List<StageInterval> {
        if (stop <= start) return emptyList()
        val epochs = night?.epochs.orEmpty().filter { it.end > start && it.start < stop }
        val usable = epochs.filter {
            it.samples >= 30 &&
                it.end - it.start >= 20000 &&
                (it.mean > .0000001 || it.variance > .000000000001)
        }
        if (usable.sumOf { it.end - it.start } < 600000) {
            return listOf(StageInterval(start, stop, EstimatedStage.UNKNOWN))
        }
        val values = usable.map { it.mean }.sorted()
        val floor = values[(values.size * .2).toInt()]
        val upper = values[(values.size * .9).toInt().coerceAtMost(values.lastIndex)]
        val minimumSignal = if (night?.mode == "sound") .002 else .004
        val spread = max(upper - floor, minimumSignal)
        val result = mutableListOf<StageInterval>()
        fun add(a: Long, b: Long, stage: EstimatedStage) {
            if (b <= a) return
            val previous = result.lastOrNull()
            if (previous != null && previous.end == a && previous.stage == stage)
                result[result.lastIndex] = previous.copy(end = b)
            else result.add(StageInterval(a, b, stage))
        }
        var cursor = start
        var quietCount = 0
        for (epoch in epochs) {
            val a = maxOf(cursor, epoch.start, start)
            val b = minOf(epoch.end, stop)
            if (b <= a) continue
            if (a > cursor) {
                add(cursor, a, EstimatedStage.UNKNOWN)
                quietCount = 0
            }
            val normalized = (epoch.mean - floor) / spread
            val valid =
                epoch.samples >= 30 &&
                    epoch.end - epoch.start >= 20000 &&
                    (epoch.mean > .0000001 || epoch.variance > .000000000001)
            quietCount = if (valid && normalized < .25 && !epoch.interactive) quietCount + 1 else 0
            val minutes = (a - start) / 60000.0
            val cyclePosition = minutes % 100
            val variability = epoch.variance / max(epoch.mean * epoch.mean, .00000001)
            val stage =
                when {
                    !valid -> EstimatedStage.UNKNOWN
                    epoch.interactive || normalized > 1.35 -> EstimatedStage.AWAKE
                    minutes < 15 -> EstimatedStage.LIGHT
                    quietCount >= 6 && minutes < 240 && cyclePosition < 65 -> EstimatedStage.DEEP
                    minutes > 70 && cyclePosition >= 70 && normalized < .65 && variability > .6 ->
                        EstimatedStage.REM
                    else -> EstimatedStage.LIGHT
                }
            add(a, b, stage)
            cursor = b
        }
        add(cursor, stop, EstimatedStage.UNKNOWN)
        return result
    }
}
