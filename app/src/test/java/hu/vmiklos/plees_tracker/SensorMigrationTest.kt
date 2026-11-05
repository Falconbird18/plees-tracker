/*
 * Copyright 2026 Plees Tracker contributors
 *
 * SPDX-License-Identifier: MIT
 */
package hu.vmiklos.plees_tracker

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SensorMigrationTest {
    @Test
    fun migrationKeepsExistingSleepAndAddsEmptySensorData() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val path = context.getDatabasePath("sensor-migration")
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                "CREATE TABLE Sleep (sid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "start_date INTEGER NOT NULL, stop_date INTEGER NOT NULL, " +
                    "rating INTEGER NOT NULL, " +
                    "comment TEXT NOT NULL, wakes INTEGER NOT NULL, " +
                    "health_connect_id TEXT NOT NULL, " +
                    "health_connect_version INTEGER NOT NULL, " +
                    "health_connect_synced_version INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX index_Sleep_health_connect_id ON Sleep(health_connect_id)")
            db.execSQL(
                "CREATE TABLE health_connect_deletion (" +
                    "health_connect_id TEXT NOT NULL PRIMARY KEY, start_date INTEGER NOT NULL)"
            )
            db.execSQL("INSERT INTO Sleep VALUES (1, 1000, 2000, 4, 'kept', 2, '', 0, -1)")
            db.version = 5
        }
        val database =
            Room.databaseBuilder(context, AppDatabase::class.java, "sensor-migration")
                .addMigrations(MIGRATION_5_6)
                .build()
        try {
            val sleep = database.sleepDao().getAll().single()
            assertEquals("kept", sleep.comment)
            assertEquals(4L, sleep.rating)
            assertEquals("", sleep.sensorData)
        } finally {
            database.close()
        }
    }
}
