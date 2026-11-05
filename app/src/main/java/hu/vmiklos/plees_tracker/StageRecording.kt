/*
 * Copyright 2026 Plees Tracker contributors
 *
 * SPDX-License-Identifier: MIT
 */
package hu.vmiklos.plees_tracker

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Crash-resilient rolling aggregates. Every completed 30-second epoch is written atomically. */
object StageRecording {
    private var active = false
    private var sessionStart = 0L
    private var mode = "motion"
    private var issue = ""
    private val epochs = mutableListOf<SensorEpoch>()
    private var epochStart = 0L
    private var lastTime = 0L
    private var firstTime = 0L
    private var count = 0
    private var mean = 0.0
    private var m2 = 0.0
    private var screen = false

    private fun file(context: Context) =
        AtomicFile(File(context.noBackupFilesDir, "sensor-night.json"))

    @Synchronized
    fun begin(context: Context, start: Long, sensorMode: String) {
        if (active && sessionStart == start) return
        epochs.clear()
        val stored = read(context)
        if (stored.first == start) epochs.addAll(stored.second?.epochs.orEmpty())
        sessionStart = start
        mode = stored.second?.takeIf { stored.first == start }?.mode ?: sensorMode
        issue = ""
        reset()
        active = true
        save(context)
    }

    @Synchronized
    fun add(context: Context, time: Long, activity: Double, interactive: Boolean) {
        if (!active || !activity.isFinite() || time < sessionStart) return
        val aligned = sessionStart + ((time - sessionStart) / 30000) * 30000
        if (count > 0 && aligned != epochStart) flush(context)
        if (count == 0) {
            epochStart = aligned
            firstTime = time
        }
        lastTime = time
        count++
        val delta = activity - mean
        mean += delta / count
        m2 += delta * (activity - mean)
        screen = screen || interactive
    }

    @Synchronized
    fun problem(context: Context, reason: String) {
        if (!active) return
        issue = reason
        save(context)
    }

    @Synchronized
    fun flush(context: Context) {
        if (active && count > 0) {
            val end = minOf(epochStart + 30000, maxOf(firstTime + 1, lastTime + 1))
            epochs.add(SensorEpoch(firstTime, end, mean, m2 / count, count, screen))
            reset()
            save(context)
        }
    }

    @Synchronized
    fun finish(context: Context, expectedStart: Long): String {
        if (active && sessionStart == expectedStart) {
            flush(context)
            active = false
        }
        val saved = read(context)
        return if (saved.first == expectedStart) saved.second?.encode().orEmpty() else ""
    }

    @Synchronized
    fun discardFinished(context: Context, expectedStart: Long) {
        if (!active && read(context).first == expectedStart) file(context).delete()
    }

    @Synchronized
    fun pause(context: Context) {
        flush(context)
        active = false
    }

    private fun reset() {
        count = 0
        mean = 0.0
        m2 = 0.0
        screen = false
    }

    private fun save(context: Context) {
        val atomic = file(context)
        val stream = atomic.startWrite()
        try {
            val json =
                org.json
                    .JSONObject(SensorNight(mode, epochs.toList(), issue).encode())
                    .put("sessionStart", sessionStart)
                    .toString()
            stream.write(json.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (exception: Exception) {
            atomic.failWrite(stream)
            throw exception
        }
    }

    private fun read(context: Context): Pair<Long, SensorNight?> =
        try {
            val json = file(context).openRead().bufferedReader().use { it.readText() }
            org.json.JSONObject(json).getLong("sessionStart") to SensorNight.decode(json)
        } catch (_: Exception) {
            0L to null
        }
}
