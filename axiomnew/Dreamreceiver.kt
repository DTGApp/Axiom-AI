package com.axiom.axiomnew

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

// ════════════════════════════════════════════════════════════════════════════════
//  ChargingReceiver
//
//  Replaces the old fixed 8 PM AlarmManager alarm.
//
//  NEW behaviour:
//    • Phone plugged in AFTER 20:00  → dream cycle starts immediately
//    • Phone plugged in BEFORE 20:00 → one-shot alarm set for 20:00 tonight
//    • DEFERRED_DREAM alarm fires     → re-checks charging, starts if plugged in
//    • BOOT_COMPLETED                 → reschedules daily/weekly usage alarms
//    • DAILY_USAGE_SCAN (03:00)       → incremental 2-day usage analysis
//    • WEEKLY_DEEP_SCAN (Sun 03:30)   → full 30-day usage analysis
// ════════════════════════════════════════════════════════════════════════════════
class ChargingReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "AxiomCharging"

        const val PREFS_NAME        = "axiom_prefs"
        const val KEY_LAST_DREAM_TS = "last_dream_ts"
        const val KEY_LAST_USAGE_TS = "last_usage_ts"

        const val DREAM_AFTER_HOUR = 17               // 5 PM — triggers on any evening charge
        const val MIN_DREAM_GAP_MS = 6L * 3600 * 1000L   // retry after 6h if previous dream failed
        const val MIN_USAGE_GAP_MS = 23L * 3600 * 1000L

        const val ACTION_DEFERRED_DREAM   = "com.axiom.axiomnew.DEFERRED_DREAM"
        const val ACTION_DAILY_USAGE_SCAN = "com.axiom.axiomnew.DAILY_USAGE_SCAN"
        const val ACTION_WEEKLY_DEEP_SCAN = "com.axiom.axiomnew.WEEKLY_DEEP_SCAN"

        fun scheduleDailyAlarms(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            // 03:00 every night — incremental 2-day usage scan
            val dailyPi = PendingIntent.getBroadcast(
                context, 100,
                Intent(context, ChargingReceiver::class.java).apply { action = ACTION_DAILY_USAGE_SCAN },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val dailyCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 3); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, dailyCal.timeInMillis, AlarmManager.INTERVAL_DAY, dailyPi)

            // Sunday 03:30 — weekly full 30-day scan
            val weeklyPi = PendingIntent.getBroadcast(
                context, 101,
                Intent(context, ChargingReceiver::class.java).apply { action = ACTION_WEEKLY_DEEP_SCAN },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val weeklyCal = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
                set(Calendar.HOUR_OF_DAY, 3); set(Calendar.MINUTE, 30)
                set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.WEEK_OF_YEAR, 1)
            }
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, weeklyCal.timeInMillis, AlarmManager.INTERVAL_DAY * 7, weeklyPi)
            android.util.Log.i(TAG, "Daily + weekly alarms scheduled")
        }

        fun scheduleOneShotDreamAlarm(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, 102,
                Intent(context, ChargingReceiver::class.java).apply { action = ACTION_DEFERRED_DREAM },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, DREAM_AFTER_HOUR)
                set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            // setExactAndAllowWhileIdle requires SCHEDULE_EXACT_ALARM permission on
            // Android 12+ (API 31+) which needs explicit user approval in Settings.
            // setAndAllowWhileIdle fires within ~15 min of target — fine for overnight
            // dream scheduling, and requires zero permissions on all Android versions.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            android.util.Log.i(TAG, "One-shot dream alarm set for $DREAM_AFTER_HOUR:00")
        }

        fun startDreamIfEligible(context: Context) {
            val prefs       = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastDreamMs = prefs.getLong(KEY_LAST_DREAM_TS, 0L) * 1000L
            val nowMs       = System.currentTimeMillis()
            if (nowMs - lastDreamMs < MIN_DREAM_GAP_MS) {
                android.util.Log.i(TAG, "Dream ran ${(nowMs - lastDreamMs) / 3600000}h ago — skipping")
                return
            }
            // Do NOT write KEY_LAST_DREAM_TS here. AxiomService.startDreamCycle()
            // writes it only AFTER all guards pass (engineReady, dreamRunning).
            // Writing it early would block retries if the engine wasn't ready yet.
            val svcIntent = Intent(context, AxiomService::class.java).apply {
                action = AxiomService.ACTION_START_DREAM
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(svcIntent)
            else context.startService(svcIntent)
            android.util.Log.i(TAG, "ACTION_START_DREAM sent to AxiomService")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                // Trigger dream on ANY charger connect if battery >= 50%
                val bm      = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                android.util.Log.i(TAG, "Charger connected, battery=$battery%")
                if (battery >= 50) {
                    startDreamIfEligible(context)
                } else {
                    android.util.Log.i(TAG, "Battery $battery% < 50 — skipping dream")
                }
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                // Start the always-on service after every reboot
                val svcIntent = Intent(context, AxiomService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(svcIntent)
                else
                    context.startService(svcIntent)
                scheduleDailyAlarms(context)
                android.util.Log.i(TAG, "Boot: AxiomService started, alarms rescheduled")
            }
            ACTION_DEFERRED_DREAM -> {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                if (bm.isCharging) startDreamIfEligible(context)
                else android.util.Log.i(TAG, "Deferred dream: not charging, skipping")
            }
            ACTION_DAILY_USAGE_SCAN -> runUsageScan(context, days = 2)
            ACTION_WEEKLY_DEEP_SCAN -> runUsageScan(context, days = 30)
        }
    }

    private fun runUsageScan(context: Context, days: Int) {
        if (!UsageAnalyser.hasPermission(context)) return
        val prefs      = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastScanMs = prefs.getLong(KEY_LAST_USAGE_TS, 0L) * 1000L
        val nowMs      = System.currentTimeMillis()
        if (days == 2 && nowMs - lastScanMs < MIN_USAGE_GAP_MS) return

        Thread {
            try {
                android.util.Log.i(TAG, "Usage scan: $days days")
                val result = UsageAnalyser.analyse(context, days)
                if (days >= 30) UsageAnalyser.saveEventLog(context, result)
                else mergeUsageLog(context, result)

                prefs.edit().putLong(KEY_LAST_USAGE_TS, nowMs / 1000).apply()
                android.util.Log.i(TAG, "Usage scan done: ${result.mappedEvents} events")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Usage scan error: ${e.message}")
            }
        }.start()
    }

    private fun mergeUsageLog(context: Context, result: UsageAnalyser.AnalysisResult) {
        try {
            val usageFile = File(context.filesDir, "axiom_usage.json")
            val existing  = if (usageFile.exists()) JSONArray(usageFile.readText()) else JSONArray()
            val allItems  = mutableListOf<Pair<Long, JSONObject>>()
            for (i in 0 until existing.length()) {
                val o = existing.getJSONObject(i)
                allItems.add(o.optLong("ts") to o)
            }
            result.events.forEach { ev ->
                allItems.add(ev.timestampMs / 1000 to JSONObject().apply {
                    put("pkg", ev.packageName); put("app", ev.appLabel)
                    put("intent", ev.intent);   put("hour", ev.hour)
                    put("dow", ev.dayOfWeek);   put("ts", ev.timestampMs / 1000)
                })
            }
            val merged = JSONArray()
            allItems.sortedByDescending { it.first }.take(500).forEach { merged.put(it.second) }
            usageFile.writeText(merged.toString(2))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Merge log error: ${e.message}")
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════════
//  DreamReceiver  (kept for manual adb test trigger only)
//  Real scheduling handled by ChargingReceiver above.
//  Test: adb shell am broadcast -n com.axiom.axiomnew/.DreamReceiver
// ════════════════════════════════════════════════════════════════════════════════
class DreamReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ChargingReceiver.startDreamIfEligible(context)
    }
}

// ════════════════════════════════════════════════════════════════════════════════
//  DreamService
//  Foreground service — runs the full dream cycle even when app is closed.
// ════════════════════════════════════════════════════════════════════════════════
class DreamService : Service() {

    companion object {
        init { System.loadLibrary("axiom_engine") }
        const val NOTIF_CHANNEL = "axiom_dream"
        const val NOTIF_ID      = 99

        // Broadcast actions — received by MainActivity to update dream UI
        const val ACTION_DREAM_STARTED  = "com.axiom.axiomnew.DREAM_STARTED"
        const val ACTION_DREAM_COMPLETE = "com.axiom.axiomnew.DREAM_COMPLETE"
        const val EXTRA_DREAM_RESULT    = "dream_result_json"
        const val PREFS_DREAM_UI        = "axiom_dream_ui"
        const val KEY_DREAM_STATE       = "dream_state"   // "idle"|"dreaming"|"complete"|"missed"
        const val KEY_DREAM_RESULT_JSON = "dream_result_json"
        const val KEY_DREAM_TS          = "dream_ts"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createDreamChannel()
        // Dream logic moved to AxiomService.startDreamCycle() to use
        // the already-loaded AxiomEngine JNI. This service is kept only
        // for its companion object constants and notification channel.
        startForeground(NOTIF_ID,
            androidx.core.app.NotificationCompat.Builder(this, NOTIF_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Axiom Dreaming…")
                .setContentText("Learning in progress")
                .setOngoing(true)
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Actual dream logic is in AxiomService.startDreamCycle().
        // If this service is ever started by mistake, just stop immediately.
        stopSelf()
        return START_NOT_STICKY
    }

    private fun createDreamChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(NOTIF_CHANNEL, "Axiom Dreaming", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while Axiom consolidates learning" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
        }
    }
}