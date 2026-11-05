/*
 * Copyright 2026 Plees Tracker contributors
 *
 * SPDX-License-Identifier: MIT
 */
package hu.vmiklos.plees_tracker

import java.io.ByteArrayOutputStream
import java.io.StringReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepStageEstimatorTest {
    private val start = 1800000000000L

    private fun epoch(
        index: Int,
        mean: Double = .01,
        variance: Double = .00001,
        interactive: Boolean = false,
    ) =
        SensorEpoch(
            start + index * 30000L,
            start + (index + 1) * 30000L,
            mean,
            variance,
            300,
            interactive,
        )

    @Test
    fun quietMeasuredActivityCanProduceDeepAndLaterRemEstimates() {
        val night = SensorNight("motion", (0..239).map { epoch(it, .01, .0001) })
        val stages = SleepStageEstimator.estimate(night, start, start + 7200000)
        assertTrue(stages.any { it.stage == EstimatedStage.DEEP })
        assertTrue(stages.any { it.stage == EstimatedStage.REM })
    }

    @Test
    fun missingAndShortRecordingsNeverProduceSleepStages() {
        assertEquals(
            EstimatedStage.UNKNOWN,
            SleepStageEstimator.estimate(null, start, start + 3600000).single().stage,
        )
        val short = SensorNight("motion", (0..10).map { epoch(it) })
        assertEquals(
            EstimatedStage.UNKNOWN,
            SleepStageEstimator.estimate(short, start, start + 330000).single().stage,
        )
        val silent = SensorNight("sound", (0..60).map { epoch(it, 0.0, 0.0) })
        assertEquals(
            EstimatedStage.UNKNOWN,
            SleepStageEstimator.estimate(silent, start, start + 1830000).single().stage,
        )
    }

    @Test
    fun interruptionsStayUnknownAndDurationsAreConserved() {
        val night = SensorNight("motion", (0..29).map { epoch(it) } + (50..89).map { epoch(it) })
        val stop = start + 90 * 30000
        val result = SleepStageEstimator.estimate(night, start, stop)
        assertEquals(stop - start, result.sumOf { it.end - it.start })
        assertTrue(
            result.any {
                it.start == start + 30 * 30000 &&
                    it.end == start + 50 * 30000 &&
                    it.stage == EstimatedStage.UNKNOWN
            }
        )
        assertTrue(result.zipWithNext().all { (a, b) -> a.end == b.start })
    }

    @Test
    fun screenInteractionAndLargeActivityAreAwake() {
        val rows = (0..99).map { epoch(it, interactive = it in 40..45) }.toMutableList()
        rows[60] = epoch(60, .5, .1)
        val stages =
            SleepStageEstimator.estimate(SensorNight("motion", rows), start, start + 3000000)
        assertTrue(
            stages.any {
                it.stage == EstimatedStage.AWAKE &&
                    it.start <= start + 40 * 30000 &&
                    it.end >= start + 46 * 30000
            }
        )
        assertTrue(
            stages.any {
                it.stage == EstimatedStage.AWAKE &&
                    it.start <= start + 60 * 30000 &&
                    it.end >= start + 61 * 30000
            }
        )
    }

    @Test
    fun editingBoundsClipsSensorDataWithoutInventingCoverage() {
        val night = SensorNight("motion", (0..39).map { epoch(it) })
        val result = SleepStageEstimator.estimate(night, start + 60000, start + 1800000)
        assertEquals(start + 60000, result.first().start)
        assertEquals(start + 1800000, result.last().end)
        assertEquals(EstimatedStage.UNKNOWN, result.last().stage)
    }

    @Test
    fun sensorDataSurvivesCsvBackupAndLegacyCsvStillImports() = runBlocking {
        val sleep =
            Sleep().apply {
                start = this@SleepStageEstimatorTest.start
                stop = start + 1200000
                sensorData = SensorNight("motion", (0..39).map { epoch(it) }).encode()
            }
        val output = ByteArrayOutputStream()
        DataModel.writeSleepsCsv(listOf(sleep), output, false)
        val restored = DataModel.parseSleepsCsv(StringReader(output.toString("UTF-8")))!!.single()
        assertEquals(sleep.sensorData, restored.sensorData)
        val legacy =
            DataModel.parseSleepsCsv(
                StringReader(
                    "sid,start,stop,rating,comment,wakes\n1,1800000000000,1800003600000,3,note,1\n"
                )
            )!!
                .single()
        assertEquals("", legacy.sensorData)
    }

    @Test
    fun invalidPayloadsAreRejected() {
        assertNull(SensorNight.decode("not json"))
        assertNull(SensorNight.decode("{\"version\":99,\"mode\":\"motion\",\"epochs\":[]}"))
        val night = SensorNight("motion", listOf(epoch(0))).encode()
        assertEquals(1, SensorNight.decode(night)!!.epochs.size)
    }
}
