/*
 * Axiom — On-Device AI Assistant for Android
 * Copyright (C) 2024 Rayad
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.axiom.axiomnew

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import org.json.JSONObject
import java.util.Calendar

// ════════════════════════════════════════════════════════════════════════════════
//  PhoneModeManager  —  Feature 3: Context-aware automatic phone mode switching
//
//  Detects what the user is likely doing (sleeping, commuting, working, gaming)
//  from sensor data without asking, and silently applies the right phone mode.
//
//  This is the "it just knew" moment:
//    • 10:30 PM + charging + no recent pickups → enables Sleep mode (DND + dim)
//    • 8 AM weekday + BT connected + wifi off → enables Commute mode (media vol up)
//    • 9 AM Mon–Fri + known wifi → enables Work mode (notifications silenced)
//
//  The LLM is used as a decision engine: given current sensor context and
//  the user's learned temporal patterns, it decides which mode to apply.
//
//  Rules:
//    • Never switches modes more than once per 30 minutes
//    • Stores last mode in prefs so it can be undone on user request
//    • DND policy change requires MANAGE_NOTIFICATIONS — gracefully degrades
//      to AudioManager ringer adjustments if DND not available
//    • All changes are logged with reason for transparency
// ════════════════════════════════════════════════════════════════════════════════
class PhoneModeManager(private val ctx: Context) {

    companion object {
        const val PREFS_NAME        = "axiom_mode"
        const val KEY_CURRENT_MODE  = "current_mode"
        const val KEY_LAST_SWITCH   = "last_switch_ts"
        const val KEY_MODE_LOG      = "mode_log"
        const val MIN_SWITCH_GAP_MS = 30L * 60 * 1000   // 30 minutes between switches

        // Mode identifiers
        const val MODE_NORMAL   = "NORMAL"
        const val MODE_SLEEP    = "SLEEP"
        const val MODE_COMMUTE  = "COMMUTE"
        const val MODE_WORK     = "WORK"
        const val MODE_FOCUS    = "FOCUS"
        const val MODE_GAMING   = "GAMING"

        // Per-mode volume behaviour preferences
        // Stored as "pref_<MODE>" = one of the VolumePref enum names
        const val PREF_PREFIX        = "pref_"
        const val KEY_CANCEL_UNTIL   = "cancel_until_ts"   // epoch-sec: suppress until this time

        // Broadcast action — sent by notification "Cancel for today" action
        const val ACTION_CANCEL_MODE = "com.axiom.axiomnew.ACTION_CANCEL_MODE"
    }

    // ── Volume behaviour the user can choose per mode ──────────────────────────
    enum class VolumePref { SILENT, VIBRATE, LOW_VOLUME }

    data class SensorSnapshot(
        val hour:      Int,
        val dayOfWeek: Int,      // Calendar.MONDAY = 2
        val battery:   Int,
        val charging:  Boolean,
        val wifiOn:    Boolean,
        val btOn:      Boolean,
        val isWeekend: Boolean,
        val timeSlot:  String    // "morning", "daytime", "evening", "night"
    )

    data class ModeDecision(
        val mode:   String,
        val reason: String,
        val applied:Boolean
    )

    // ── Main evaluation ───────────────────────────────────────────────────────
    /**
     * Evaluates current context and applies mode if appropriate.
     * Call this from the AxiomService sensor loop (every 5 min).
     * Returns null if no switch warranted or too soon since last switch.
     */
    fun evaluate(): ModeDecision? {
        if (!canSwitch()) return null
        if (isCancelledForToday()) return null   // user cancelled modes for today

        val snap     = readSensors()
        val current  = getCurrentMode()
        val decision = decide(snap, current)

        if (decision != null && decision.mode != current) {
            applyMode(decision.mode, snap)
            recordSwitch(decision)
            return decision
        }
        return null
    }

    fun getCurrentMode(): String =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CURRENT_MODE, MODE_NORMAL) ?: MODE_NORMAL

    fun getModeLog(): List<String> {
        val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE_LOG, "") ?: ""
        return raw.lines().filter { it.isNotBlank() }.takeLast(20)
    }

    fun resetToNormal() {
        applyMode(MODE_NORMAL, readSensors())
        recordSwitch(ModeDecision(MODE_NORMAL, "User requested reset", true))
    }

    // ── Per-mode volume preference ─────────────────────────────────────────────
    fun getVolumePref(mode: String): VolumePref {
        val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("$PREF_PREFIX$mode", null)
        return runCatching { VolumePref.valueOf(raw ?: "") }.getOrElse {
            // Sensible defaults per mode
            when (mode) {
                MODE_SLEEP  -> VolumePref.SILENT
                MODE_WORK   -> VolumePref.VIBRATE
                MODE_FOCUS  -> VolumePref.SILENT
                else        -> VolumePref.LOW_VOLUME
            }
        }
    }

    fun setVolumePref(mode: String, pref: VolumePref) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("$PREF_PREFIX$mode", pref.name)
            .apply()
        // Re-apply immediately if this mode is currently active
        if (getCurrentMode() == mode) applyMode(mode, readSensors())
    }

    // ── Cancel for today ───────────────────────────────────────────────────────
    // Suppresses automatic mode switching until midnight tonight.
    fun cancelForToday() {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 23)
            set(java.util.Calendar.MINUTE, 59)
            set(java.util.Calendar.SECOND, 59)
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_CANCEL_UNTIL, cal.timeInMillis / 1000L)
            .apply()
        // Immediately restore Normal
        applyMode(MODE_NORMAL, readSensors())
        recordSwitch(ModeDecision(MODE_NORMAL, "User cancelled mode for today", true))
    }

    fun isCancelledForToday(): Boolean {
        val until = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_CANCEL_UNTIL, 0L)
        return System.currentTimeMillis() / 1000L < until
    }

    // ── Can we switch? ────────────────────────────────────────────────────────
    private fun canSwitch(): Boolean {
        val prefs  = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastTs = prefs.getLong(KEY_LAST_SWITCH, 0L)
        return System.currentTimeMillis() - lastTs > MIN_SWITCH_GAP_MS
    }

    // ── Sensor snapshot ───────────────────────────────────────────────────────
    private fun readSensors(): SensorSnapshot {
        val cal   = Calendar.getInstance()
        val hour  = cal.get(Calendar.HOUR_OF_DAY)
        val dow   = cal.get(Calendar.DAY_OF_WEEK)

        val battery  = runCatching {
            (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(-1)

        val charging = runCatching {
            (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging
        }.getOrDefault(false)

        val wifiOn = runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getNetworkCapabilities(cm.activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.getOrDefault(false)

        val btOn = runCatching {
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter?.isEnabled == true
        }.getOrDefault(false)

        val isWeekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
        val timeSlot  = when (hour) {
            in 5..8   -> "morning"
            in 9..17  -> "daytime"
            in 18..21 -> "evening"
            else      -> "night"
        }

        return SensorSnapshot(hour, dow, battery, charging, wifiOn, btOn, isWeekend, timeSlot)
    }

    // ── Decision logic ────────────────────────────────────────────────────────
    /**
     * Rule-based decision with LLM verification for ambiguous cases.
     * Hard rules fire immediately; borderline cases ask the LLM.
     */
    private fun decide(snap: SensorSnapshot, current: String): ModeDecision? {

        // Hard rule: Sleep mode
        // 10 PM – 6 AM + charging → very strong sleep signal
        if ((snap.hour >= 22 || snap.hour < 6) && snap.charging && current != MODE_SLEEP) {
            return ModeDecision(MODE_SLEEP,
                "It's ${formatHour(snap.hour)} and your phone is charging", false)
        }

        // Hard rule: Exit sleep on morning
        if (snap.hour in 6..8 && current == MODE_SLEEP) {
            return ModeDecision(MODE_NORMAL, "Good morning — sleep mode ended", false)
        }

        // Hard rule: Commute mode
        // 7–9 AM or 5–7 PM weekday + BT on + no wifi
        val isCommuteTime = (snap.hour in 7..9 || snap.hour in 17..19)
        if (isCommuteTime && !snap.isWeekend && snap.btOn && !snap.wifiOn && current != MODE_COMMUTE) {
            return ModeDecision(MODE_COMMUTE,
                "Bluetooth on, no WiFi at ${formatHour(snap.hour)} — looks like you're commuting", false)
        }

        // Hard rule: Work mode
        // 9 AM–5 PM weekday + wifi on
        if (snap.hour in 9..17 && !snap.isWeekend && snap.wifiOn
            && current !in listOf(MODE_WORK, MODE_COMMUTE)) {
            return ModeDecision(MODE_WORK,
                "Weekday ${formatHour(snap.hour)} on WiFi — enabling work focus", false)
        }

        // For ambiguous transitions, ask the LLM
        if (AxiomEngine.isEngineReady()) {
            return askLlmDecision(snap, current)
        }

        return null
    }

    private fun askLlmDecision(snap: SensorSnapshot, current: String): ModeDecision? {
        val prompt = buildString {
            appendLine("Decide the best phone mode for this context. Reply with ONLY one word: NORMAL, SLEEP, COMMUTE, WORK, FOCUS, or GAMING.")
            appendLine()
            appendLine("Current mode: $current")
            appendLine("Time: ${formatHour(snap.hour)}, ${if (snap.isWeekend) "weekend" else "weekday"}")
            appendLine("Battery: ${snap.battery}%${if (snap.charging) ", charging" else ""}")
            appendLine("WiFi: ${if (snap.wifiOn) "on" else "off"}, Bluetooth: ${if (snap.btOn) "on" else "off"}")
            append("Mode:")
        }

        val result = runCatching { AxiomEngine.conversationalAnswer(prompt) }
            .getOrNull()?.trim()?.uppercase() ?: return null

        val validModes = setOf(MODE_NORMAL, MODE_SLEEP, MODE_COMMUTE, MODE_WORK, MODE_FOCUS, MODE_GAMING)
        val mode = validModes.find { result.startsWith(it) } ?: return null

        if (mode == current) return null
        return ModeDecision(mode, "Context-aware switch at ${formatHour(snap.hour)}", false)
    }

    // ── Apply mode ────────────────────────────────────────────────────────────
    private fun applyMode(mode: String, snap: SensorSnapshot) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        when (mode) {
            MODE_SLEEP -> {
                applyVolumePref(am, MODE_SLEEP)
                trySetDnd(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            }
            MODE_COMMUTE -> {
                // Restore ringer, boost media (for headphones)
                runCatching { am.ringerMode = AudioManager.RINGER_MODE_NORMAL }
                runCatching {
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC,
                        (max * 0.7).toInt(), 0)  // 70% media volume
                }
            }
            MODE_WORK -> {
                applyVolumePref(am, MODE_WORK)
                trySetDnd(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            }
            MODE_FOCUS -> {
                applyVolumePref(am, MODE_FOCUS)
                trySetDnd(NotificationManager.INTERRUPTION_FILTER_NONE)
            }
            MODE_GAMING -> {
                // Normal ringer, high media volume
                runCatching { am.ringerMode = AudioManager.RINGER_MODE_NORMAL }
                runCatching {
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
                }
            }
            MODE_NORMAL -> {
                runCatching { am.ringerMode = AudioManager.RINGER_MODE_NORMAL }
                trySetDnd(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        }

        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_CURRENT_MODE, mode)
            .apply()
    }

    private fun applyVolumePref(am: AudioManager, mode: String) {
        when (getVolumePref(mode)) {
            VolumePref.SILENT      -> runCatching { am.ringerMode = AudioManager.RINGER_MODE_SILENT }
            VolumePref.VIBRATE     -> runCatching { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE }
            VolumePref.LOW_VOLUME  -> runCatching {
                am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                val max = am.getStreamMaxVolume(AudioManager.STREAM_RING)
                am.setStreamVolume(AudioManager.STREAM_RING, (max * 0.25).toInt(), 0)
            }
        }
    }

    private fun trySetDnd(filter: Int) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(filter)
            }
        }
    }

    private fun recordSwitch(decision: ModeDecision) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ts    = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
        val entry = "$ts → ${decision.mode}: ${decision.reason}"
        val log   = (prefs.getString(KEY_MODE_LOG, "") ?: "") + "\n$entry"
        prefs.edit()
            .putString(KEY_MODE_LOG, log.lines().takeLast(30).joinToString("\n"))
            .putLong(KEY_LAST_SWITCH, System.currentTimeMillis())
            .apply()

        android.util.Log.i("PhoneModeManager", entry)
    }

    private fun formatHour(hour: Int): String = when {
        hour == 0  -> "midnight"
        hour < 12  -> "${hour}:00 AM"
        hour == 12 -> "noon"
        else       -> "${hour - 12}:00 PM"
    }
}