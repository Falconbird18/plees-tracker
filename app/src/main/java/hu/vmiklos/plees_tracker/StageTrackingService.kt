/*
 * Copyright 2026 Plees Tracker contributors
 *
 * SPDX-License-Identifier: MIT
 */
package hu.vmiklos.plees_tracker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import kotlin.math.sqrt

/** Optional on-device recording; the original sensor-free timer remains available in Settings. */
class StageTrackingService : Service(), SensorEventListener {
    private var manager: SensorManager? = null
    private var worker: HandlerThread? = null
    private var recorder: AudioRecord? = null
    private var audioThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var running = false
    private var gravity: DoubleArray? = null
    private lateinit var power: PowerManager

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        DataModel.init(applicationContext, preferences)
        val start =
            DataModel.start?.time
                ?: run {
                    stopSelf()
                    return START_NOT_STICKY
                }
        if (DataModel.stop != null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mode = preferences.getString("active_sensor_mode", "manual") ?: "manual"
        if (mode == "manual") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (
            mode == "sound" &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            StageRecording.begin(this, start, mode)
            StageRecording.problem(
                this,
                "Microphone permission unavailable; no sound data collected.",
            )
            stopSelf()
            return START_NOT_STICKY
        }
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            notifications.createNotificationChannel(
                NotificationChannel(
                    "sleep_sensors",
                    getString(R.string.sensor_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        val launch =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat.Builder(this, "sleep_sensors")
                .setSmallIcon(R.drawable.ic_moon)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(
                    getString(
                        if (mode == "sound") R.string.sensor_recording_sound
                        else R.string.sensor_recording_motion
                    )
                )
                .setContentIntent(launch)
                .setOngoing(true)
                .setSilent(true)
                .build()
        val type =
            if (Build.VERSION.SDK_INT >= 34)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    (if (mode == "sound") ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            else 0
        ServiceCompat.startForeground(this, 2, notification, type)
        power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock =
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "plees:sensorTracking").apply {
                acquire(16 * 60 * 60 * 1000L)
            }
        StageRecording.begin(this, start, mode)
        running = true
        if (mode == "sound") startAudio() else startMotion()
        return START_STICKY
    }

    private fun startMotion() {
        manager = getSystemService(SENSOR_SERVICE) as SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            StageRecording.problem(this, "This device has no accelerometer.")
            stopSelf()
            return
        }
        worker = HandlerThread("sleep-motion").also { it.start() }
        val registered =
            manager?.registerListener(
                this,
                sensor,
                100000,
                android.os.Handler(worker!!.looper),
            ) == true
        if (!registered) {
            StageRecording.problem(this, "The motion sensor could not be started.")
            stopSelf()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        val time =
            System.currentTimeMillis() -
                (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1000000
        val g = gravity ?: DoubleArray(3) { event.values[it].toDouble() }.also { gravity = it }
        var magnitude = 0.0
        for (axis in 0..2) {
            g[axis] = .9 * g[axis] + .1 * event.values[axis]
            val dynamic = (event.values[axis] - g[axis]) / SensorManager.GRAVITY_EARTH
            magnitude += dynamic * dynamic
        }
        StageRecording.add(this, time, sqrt(magnitude), power.isInteractive)
        enforceDuration(time)
    }

    @Suppress("MissingPermission")
    private fun startAudio() {
        try {
            val size =
                AudioRecord.getMinBufferSize(
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            require(size > 0)
            val audio =
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(size * 2, 4096),
                )
            recorder = audio
            require(audio.state == AudioRecord.STATE_INITIALIZED)
            audio.startRecording()
            audioThread =
                Thread(
                    {
                        val buffer = ShortArray(1024)
                        try {
                            while (running) {
                                val count = audio.read(buffer, 0, buffer.size)
                                if (count <= 0)
                                    throw IllegalStateException("Microphone read failed")
                                var sum = 0.0
                                for (index in 0 until count) {
                                    val value = buffer[index] / 32768.0
                                    sum += value * value
                                }
                                val time = System.currentTimeMillis()
                                StageRecording.add(
                                    this,
                                    time,
                                    sqrt(sum / count),
                                    power.isInteractive,
                                )
                                buffer.fill(
                                    0
                                ) // Raw PCM exists only in this reusable in-memory buffer.
                                enforceDuration(time)
                            }
                        } catch (_: Exception) {
                            if (running)
                                StageRecording.problem(
                                    this,
                                    "Microphone capture was interrupted.",
                                )
                            stopSelf()
                        } finally {
                            buffer.fill(0)
                        }
                    },
                    "sleep-levels",
                )
                    .also { it.start() }
        } catch (_: Exception) {
            StageRecording.problem(this, "Microphone unavailable; no sound estimates recorded.")
            stopSelf()
        }
    }

    private fun enforceDuration(now: Long) {
        if (now - (DataModel.start?.time ?: now) >= 16 * 60 * 60 * 1000L) {
            StageRecording.problem(
                this,
                "Sensor collection stopped after 16 hours. The sleep timer continued.",
            )
            stopSelf()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        manager?.unregisterListener(this)
        worker?.quitSafely()
        try {
            recorder?.stop()
        } catch (_: IllegalStateException) {}
        audioThread?.join(500)
        recorder?.release()
        recorder = null
        StageRecording.pause(this)
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}
