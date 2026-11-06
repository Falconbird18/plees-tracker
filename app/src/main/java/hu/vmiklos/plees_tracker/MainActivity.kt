/*
 * Copyright 2023 Miklos Vajna
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mikepenz.aboutlibraries.LibsBuilder
import hu.vmiklos.plees_tracker.calendar.CalendarImport
import hu.vmiklos.plees_tracker.calendar.UserCalendar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.launch

/** The activity is the primary UI of the app: allows starting and stopping the tracking. */
class MainActivity : ExpressiveActivity(), View.OnClickListener {

    private lateinit var viewModel: MainViewModel
    private lateinit var restDashboard: RestDashboard

    // SharedPreferences keeps listeners in a WeakHashMap, so keep this as a member.
    private val sharedPreferenceListener = SharedPreferencesChangeListener()

    private val sensorPermissions =
        registerForActivityResult(RequestMultiplePermissions()) { result ->
            val mode =
                PreferenceManager.getDefaultSharedPreferences(this)
                    .getString("sensor_mode", "motion")
            if (
                mode == "sound" &&
                result[Manifest.permission.RECORD_AUDIO] != true &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, R.string.sensor_mic_denied, Toast.LENGTH_LONG).show()
            } else startNewSession()
        }

    private val exportPermissionLauncher =
        registerForActivityResult(RequestMultiplePermissions()) { permissions: Map<String, Boolean>
            ->
            checkCalendarPermissionGranted(permissions, ::exportCalendarData)
        }

    private val importPermissionLauncher =
        registerForActivityResult(RequestMultiplePermissions()) { permissions: Map<String, Boolean>
            ->
            checkCalendarPermissionGranted(permissions, ::importCalendarData)
        }

    private val importActivityResult =
        registerForActivityResult(StartActivityForResult()) { result ->
            try {
                result.data?.data?.let { uri ->
                    viewModel.importData(applicationContext, contentResolver, uri)
                    updateView()
                }
            } catch (e: Exception) {
                Log.e(TAG, "onActivityResult: importData() failed")
            }
        }

    private val exportActivityResult =
        registerForActivityResult(StartActivityForResult()) { result ->
            try {
                result.data?.data?.let { uri ->
                    viewModel.exportDataToFile(
                        applicationContext,
                        contentResolver,
                        uri,
                        showToast = true,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "onActivityResult: exportData() failed")
            }
        }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            if (it.getBooleanExtra("startStop", false)) {
                onClick(findViewById(R.id.start_stop_layout))
                it.removeExtra("startStop")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Retire the former XML dashboard fragment when Android restores a pre-redesign task.
        supportFragmentManager.fragments.filterIsInstance<StatsFragment>().forEach { fragment ->
            supportFragmentManager.beginTransaction().remove(fragment).commitNow()
        }

        sharedPreferenceListener.applyTheme(PreferenceManager.getDefaultSharedPreferences(this))

        val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        preferences.registerOnSharedPreferenceChangeListener(sharedPreferenceListener)
        DataModel.init(applicationContext, preferences)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    HealthConnectBackend.scheduleReconcile(applicationContext)
                }
            }
        }

        viewModel =
            ViewModelProvider.AndroidViewModelFactory(application).create(MainViewModel::class.java)

        setContentView(R.layout.activity_main)

        supportActionBar?.hide()
        setSupportActionBar(findViewById(R.id.toolbar))
        DataModel.handleWindowInsets(this)
        restDashboard = RestDashboard(this, savedInstanceState?.getInt("selected_night") ?: 0)
        val navigation = findViewById<BottomNavigationView>(R.id.navigation)
        navigation.setOnItemSelectedListener { item ->
            findViewById<View>(R.id.tracking_panel).visibility =
                if (item.itemId == R.id.nav_sleep) View.VISIBLE else View.GONE
            findViewById<View>(R.id.main_scroll).visibility =
                if (item.itemId == R.id.nav_statistics) View.VISIBLE else View.GONE
            findViewById<View>(R.id.history_scroll).visibility =
                if (item.itemId == R.id.nav_history) View.VISIBLE else View.GONE
            true
        }
        restDashboard.onOpenNight = {
            navigation.selectedItemId = R.id.nav_statistics
            findViewById<androidx.core.widget.NestedScrollView>(R.id.main_scroll).post {
                findViewById<androidx.core.widget.NestedScrollView>(R.id.main_scroll).scrollTo(0, 0)
            }
        }
        navigation.selectedItemId = savedInstanceState?.getInt("selected_tab") ?: R.id.nav_sleep
        restDashboard.exploreButton.setOnClickListener {
            startActivity(Intent(this, GraphsActivity::class.java))
        }
        restDashboard.comparisonButton.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }
        if (!preferences.getBoolean("onboarding_complete", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.start_stop_layout).setOnClickListener(this)
        DataModel.sleepsLive.observe(this) { sleeps -> restDashboard.submitAll(sleeps.orEmpty()) }
        val sleepsAdapter = SleepsAdapter(preferences)
        val recyclerView = restDashboard.journal
        viewModel.durationSleepsLive.observe(this) { sleeps ->
            val data = sleeps.orEmpty()
            sleepsAdapter.data = data
            restDashboard.submitHistory(data)
            recyclerView.visibility = if (data.isEmpty()) View.GONE else View.VISIBLE
            restDashboard.journalEmpty.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
        }

        val recyclerViewLayout = LinearLayoutManager(this)
        recyclerView.layoutManager = recyclerViewLayout
        recyclerView.itemAnimator = DefaultItemAnimator()
        recyclerView.adapter = sleepsAdapter

        sleepsAdapter.registerAdapterDataObserver(
            object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeInserted(
                    positionStart: Int,
                    itemCount: Int,
                ) {
                    recyclerView.scrollToPosition(positionStart)
                }
            }
        )

        // Otherwise swipe on a card view deletes it.
        val itemTouchHelper =
            ItemTouchHelper(
                SleepTouchCallback(
                    applicationContext,
                    contentResolver,
                    viewModel,
                    sleepsAdapter,
                )
            )
        itemTouchHelper.attachToRecyclerView(recyclerView)
        sleepsAdapter.clickCallback = View.OnClickListener { view ->
            val position = recyclerView.getChildAdapterPosition(view)
            sleepsAdapter.data.getOrNull(position)?.let { restDashboard.selectNight(it) }
        }

        // See if the activity is triggered from the widget. If so, toggle the start/stop state.
        handleIntent(intent)

        // Schedule bedtime and wakeup reminders if already set in preferences.
        val dailyReminder = preferences.getBoolean("daily_reminder", false)
        if (dailyReminder && preferences.contains("bedtime") && preferences.contains("wakeup")) {
            val bedHour = DataModel.getBedtimeHour(preferences)
            val bedMinute = DataModel.getBedtimeMinute(preferences)
            val wakeHour = DataModel.getWakeupHour(preferences)
            val wakeMinute = DataModel.getWakeupMinute(preferences)
            scheduleReminders(bedHour, bedMinute, wakeHour, wakeMinute)
        } else {
            cancelReminders()
        }

        updateView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(
            "selected_tab",
            findViewById<BottomNavigationView>(R.id.navigation).selectedItemId,
        )
        outState.putInt("selected_night", restDashboard.selectedId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::restDashboard.isInitialized) restDashboard.dispose()
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(sharedPreferenceListener)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, MainService::class.java)
        stopService(intent)
        val mode =
            PreferenceManager.getDefaultSharedPreferences(this)
                .getString("active_sensor_mode", "manual")
        if (DataModel.start != null && DataModel.stop == null && mode != "manual") {
            ContextCompat.startForegroundService(
                this,
                Intent(this, StageTrackingService::class.java),
            )
        }
        val recyclerView = restDashboard.journal
        recyclerView.findViewHolderForAdapterPosition(0)?.let {
            // Since the adapter unconditionally gets assigned in `onCreate()`
            // it shouldn't be necessary to consider fixing it here.
            // If it is null at this point a lot more must have gone wrong as well.
            recyclerView.adapter?.onBindViewHolder(it, 0)
        }
    }

    override fun onStop() {
        super.onStop()
        val intent = Intent(this, MainService::class.java)
        if (
            DataModel.start != null &&
            DataModel.stop == null &&
            PreferenceManager.getDefaultSharedPreferences(this)
                .getString("active_sensor_mode", "manual") == "manual"
        ) {
            startService(intent)
        }
    }

    override fun onClick(view: View?) {
        if (view?.id != R.id.start_stop_layout) return
        if (DataModel.start != null && DataModel.stop == null) {
            DataModel.stop = Calendar.getInstance().time
            viewModel.stopSleep(applicationContext, contentResolver)
            stopService(Intent(this, StageTrackingService::class.java))
            val preferences = PreferenceManager.getDefaultSharedPreferences(this)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (
                preferences.getBoolean("enable_dnd", false) &&
                manager.isNotificationPolicyAccessGranted
            ) {
                manager.setInterruptionFilter(
                    preferences.getInt("current_dnd", NotificationManager.INTERRUPTION_FILTER_ALL)
                )
            }
            updateView()
            return
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val mode = preferences.getString("sensor_mode", "motion") ?: "motion"
        if (mode != "manual" && !preferences.getBoolean("sensor_setup_" + mode, false)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sensor_setup_title)
                .setMessage(
                    if (mode == "sound") R.string.sensor_setup_sound
                    else R.string.sensor_setup_motion
                )
                .setPositiveButton(R.string.sleep_action) { _, _ ->
                    preferences.edit { putBoolean("sensor_setup_" + mode, true) }
                    requestSensorPermissions()
                }
                .setNeutralButton(R.string.settings) { _, _ ->
                    startActivity(Intent(this, PreferencesActivity::class.java))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else requestSensorPermissions()
    }

    private fun requestSensorPermissions() {
        val mode =
            PreferenceManager.getDefaultSharedPreferences(this).getString("sensor_mode", "motion")
        val permissions = mutableListOf<String>()
        if (
            mode == "sound" &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        )
            permissions.add(Manifest.permission.RECORD_AUDIO)
        if (
            mode != "manual" &&
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        )
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (permissions.isEmpty()) startNewSession()
        else sensorPermissions.launch(permissions.toTypedArray())
    }

    private fun startNewSession() {
        if (DataModel.start != null && DataModel.stop == null) return
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val mode = preferences.getString("sensor_mode", "motion") ?: "motion"
        preferences.edit {
            putInt("current_dnd", manager.currentInterruptionFilter)
            putString("active_sensor_mode", mode)
        }
        DataModel.start = Calendar.getInstance().time
        DataModel.stop = null
        if (
            preferences.getBoolean("enable_dnd", false) && manager.isNotificationPolicyAccessGranted
        )
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        if (mode != "manual")
            ContextCompat.startForegroundService(
                this,
                Intent(this, StageTrackingService::class.java),
            )
        updateView()
    }

    private fun exportFileData() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "text/csv"

        // Make it less likely that we would overwrite a previous export result.
        val calendar = Calendar.getInstance()
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val fileName = "plees-tracker-" + sdf.format(calendar.time) + ".csv"
        intent.putExtra(Intent.EXTRA_TITLE, fileName)

        exportActivityResult.launch(intent)
    }

    private fun importFileData() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "text/*"
        val mimeTypes = arrayOf("text/csv", "text/comma-separated-values")
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        importActivityResult.launch(intent)
    }

    private fun updateView() {
        val button = findViewById<ExpressiveSleepButton>(R.id.start_stop_layout)
        val running = DataModel.start != null && DataModel.stop == null
        button.setText(if (running) R.string.wake_action else R.string.sleep_action)
        button.setIconResource(if (running) R.drawable.ic_sun else R.drawable.ic_moon)
        button.contentDescription =
            if (running)
                getString(
                    R.string.sensor_button_active,
                    DataModel.formatTimestamp(DataModel.start!!, DataModel.getCompactView()),
                )
            else getString(R.string.sleep_action)
        button.setTrackingState(running)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.import_calendar_data -> {
                checkForCalendarPermission(importPermissionLauncher, ::importCalendarData)
                return true
            }
            R.id.import_file_data -> {
                importFileData()
                return true
            }
            R.id.export_file_data -> {
                exportFileData()
                return true
            }
            R.id.export_calendar_data -> {
                checkForCalendarPermission(exportPermissionLauncher, ::exportCalendarData)
                return true
            }
            R.id.about -> {
                LibsBuilder()
                    .withActivityTitle(getString(R.string.about_toolbar))
                    .withAboutAppName(getString(R.string.app_name))
                    .withActivityTheme(R.style.AppTheme)
                    .withAboutDescription(getString(R.string.app_description))
                    .start(this)
                return true
            }
            R.id.documentation -> {
                val website = getString(R.string.website_link)
                open(Uri.parse(website))
                return true
            }
            R.id.settings -> {
                startActivity(Intent(this, PreferencesActivity::class.java))
                return true
            }
            R.id.delete_all_sleep -> {
                val builder = MaterialAlertDialogBuilder(this)
                builder
                    .setMessage(getString(R.string.delete_all_message))
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.delete_all_positive)) { _, _ ->
                        viewModel.deleteAllSleep()
                    }
                    .setNegativeButton(getString(R.string.delete_all_negative)) { dialog, _ ->
                        dialog.dismiss()
                    }
                val alert = builder.create()
                alert.show()
                return true
            }
            R.id.stats -> {
                startActivity(Intent(this, StatsActivity::class.java))
                return true
            }
            R.id.graphs -> {
                startActivity(Intent(this, GraphsActivity::class.java))
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    private fun checkCalendarPermissionGranted(
        granted: Map<String, Boolean>,
        onSuccess: (UserCalendar) -> Unit,
    ) {
        // Check all permissions were granted
        if (granted.values.all { it }) {
            // Start picker for calendar
            showUserCalendarPicker(onSuccess)
        } else {
            // Permission denied
            Toast.makeText(
                this,
                getString(R.string.calendar_permission_required),
                Toast.LENGTH_LONG,
            )
                .show()
        }
    }

    private fun checkForCalendarPermission(
        permissionLauncher: ActivityResultLauncher<Array<String>>,
        block: (UserCalendar) -> Unit,
    ) {
        when (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)) {
            PackageManager.PERMISSION_GRANTED -> showUserCalendarPicker(block)
            else -> {
                // Directly ask for the permissions
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR,
                    )
                )
            }
        }
    }

    private inline fun showUserCalendarPicker(crossinline block: (cal: UserCalendar) -> Unit) {

        // Fetch calendar data
        val calendars = CalendarImport.queryForCalendars(this)

        // No user calendars found, show dialog informing user
        if (calendars.isEmpty()) {
            showNoCalendarsFoundDialog()
            return
        }

        // Get name of calendar(s)
        val titles = calendars.map(UserCalendar::name).toTypedArray()
        var selectedItem = 0

        // Show User Calendar picker
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.select_calendar_dialog_title))
            .setNeutralButton(getString(R.string.select_calendar_dialog_negative), null)
            .setPositiveButton(getString(R.string.select_calendar_dialog_positive)) { _, _ ->
                block.invoke(calendars[selectedItem])
            }
            .setSingleChoiceItems(titles, selectedItem) { _, newSelection ->
                selectedItem = newSelection
            }
            .show()
    }

    private fun importCalendarData(selectedItem: UserCalendar) {
        viewModel.importDataFromCalendar(this, selectedItem.id)
    }

    private fun exportCalendarData(selectedItem: UserCalendar) {
        viewModel.exportDataToCalendar(this, selectedItem.id)
    }

    private fun showNoCalendarsFoundDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.select_calendar_dialog_title))
            .setMessage(getString(R.string.import_dialog_error_message))
            .setNegativeButton(getString(R.string.dismiss), null)
            .show()
    }

    private fun open(link: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, link)
        startActivity(intent)
    }

    // Schedule two daily repeating alarms (one for bedtime and one for wakeup)
    private fun scheduleReminders(bedHour: Int, bedMinute: Int, wakeHour: Int, wakeMinute: Int) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Schedule bedtime alarm
        val bedTimeCalendar =
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, bedHour)
                set(Calendar.MINUTE, bedMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_MONTH, 1)
                }
            }
        val bedIntent =
            Intent(this, ReminderReceiver::class.java).apply {
                putExtra("reminder_type", "bedtime")
            }
        val bedPendingIntent =
            PendingIntent.getBroadcast(
                this,
                100,
                bedIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            bedTimeCalendar.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            bedPendingIntent,
        )

        // Schedule wakeup alarm
        val wakeTimeCalendar =
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, wakeHour)
                set(Calendar.MINUTE, wakeMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_MONTH, 1)
                }
            }
        val wakeIntent =
            Intent(this, ReminderReceiver::class.java).apply {
                putExtra("reminder_type", "wakeup")
            }
        val wakePendingIntent =
            PendingIntent.getBroadcast(
                this,
                101,
                wakeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            wakeTimeCalendar.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            wakePendingIntent,
        )
    }

    private fun cancelReminders() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val bedIntent =
            Intent(this, ReminderReceiver::class.java).apply {
                putExtra("reminder_type", "bedtime")
            }
        val bedPendingIntent =
            PendingIntent.getBroadcast(
                this,
                100,
                bedIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        alarmManager.cancel(bedPendingIntent)

        val wakeIntent =
            Intent(this, ReminderReceiver::class.java).apply {
                putExtra("reminder_type", "wakeup")
            }
        val wakePendingIntent =
            PendingIntent.getBroadcast(
                this,
                101,
                wakeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        alarmManager.cancel(wakePendingIntent)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
