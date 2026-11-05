/*
 * Copyright 2026 Plees Tracker contributors
 *
 * SPDX-License-Identifier: MIT
 */
package hu.vmiklos.plees_tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StageRecordingTest {
    @Test
    fun aSinglePartialSampleCannotInvalidateTheCompletedNight() {
        val context = RuntimeEnvironment.getApplication()
        val start = 1800100000000L
        StageRecording.begin(context, start, "sound")
        for (i in 0..299) StageRecording.add(context, start + i * 100, .01, false)
        StageRecording.add(context, start + 33000, .02, false)
        val data = StageRecording.finish(context, start)
        assertEquals(2, SensorNight.decode(data)!!.epochs.size)
        StageRecording.add(context, start + 34000, .5, true)
        StageRecording.problem(context, "late write")
        assertEquals(data, StageRecording.finish(context, start))
        StageRecording.discardFinished(context, start)
        assertEquals("", StageRecording.finish(context, start))
    }

    @Test
    fun interruptedRecordingResumesWithoutFillingTheGap() {
        val context = RuntimeEnvironment.getApplication()
        val start = 1800000000000L
        StageRecording.begin(context, start, "motion")
        for (i in 0..299) StageRecording.add(context, start + i * 100, .01, false)
        StageRecording.pause(context)
        StageRecording.begin(context, start, "motion")
        for (i in 0..299) StageRecording.add(context, start + 90000 + i * 100, .02, false)
        val result = SensorNight.decode(StageRecording.finish(context, start))!!
        assertEquals(2, result.epochs.size)
        assertTrue(result.epochs[1].start - result.epochs[0].end >= 60000)
        assertEquals("", StageRecording.finish(context, start + 1))
    }
}
