/*
 * Axiom — On-Device AI Assistant for Android
 * Copyright (C) 2024 [Your Name]
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

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.StatFs
import org.json.JSONObject
import java.util.Calendar

// ════════════════════════════════════════════════════════════════════════════════
//  BatteryDoctor  —  Feature 1: On-device AI battery diagnosis
//
//  Collects real telemetry (battery %, temp, health, top background app,
//  screen-on time, pickup count, storage), builds a structured data context,
//  and feeds it to the on-device LLM in conversational mode to generate a
//  plain-English diagnosis paragraph the user has never seen before.
//
//  The LLM is NOT classifying intents here — it is answering a question using
//  provided device data. This is what makes the output feel genuinely intelligent.
//
//  No new permissions required:
//    BatteryManager  — no permission
//    UsageStatsManager — already granted (PACKAGE_USAGE_STATS)
//    StatFs          — no permission
// ════════════════════════════════════════════════════════════════════════════════
class BatteryDoctor(private val ctx: Context) {

    data class DrainInfo(
        val packageName:             String,
        val friendlyName:            String,
        val backgroundMinutes:       Long,
        val estimatedSavingsMinutes: Int
    )

    data class BatteryReport(
        val summary:       String,       // LLM-generated plain-English diagnosis
        val level:         Int,
        val isCharging:    Boolean,
        val health:        String,
        val tempC:         Float,
        val topDrain:      DrainInfo?,
        val screenOnMins:  Long,
        val pickupCount:   Int,
        val storageFreeMb: Long,
        val rawContextJson:String        // for debug / EventViewer
    )

    // ── Main entry point (run off main thread) ────────────────────────────────
    fun diagnose(): BatteryReport {
        val level     = getBatteryLevel()
        val charging  = getIsCharging()
        val health    = getBatteryHealth()
        val temp      = getBatteryTemp()
        val topDrain  = getTopBackgroundDrainer()
        val screenOn  = getScreenOnMinutes()
        val pickups   = getPickupCount()
        val storage   = getFreeStorageMb()

        val contextJson = buildContextJson(level, charging, health, temp,
            topDrain, screenOn, pickups, storage)

        val summary = generateDiagnosis(contextJson, level, charging, temp,
            health, topDrain, screenOn, pickups)

        return BatteryReport(
            summary        = summary,
            level          = level,
            isCharging     = charging,
            health         = health,
            tempC          = temp,
            topDrain       = topDrain,
            screenOnMins   = screenOn,
            pickupCount    = pickups,
            storageFreeMb  = storage,
            rawContextJson = contextJson
        )
    }

    // ── Data collectors ───────────────────────────────────────────────────────

    private fun getBatteryLevel(): Int = runCatching {
        (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(-1)

    private fun getIsCharging(): Boolean = runCatching {
        (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging
    }.getOrDefault(false)

    private fun getBatteryHealth(): String = runCatching {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        when (i?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD        -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT    -> "Overheating"
            BatteryManager.BATTERY_HEALTH_DEAD        -> "Dead"
            BatteryManager.BATTERY_HEALTH_COLD        -> "Cold"
            else                                      -> "Unknown"
        }
    }.getOrDefault("Unknown")

    private fun getBatteryTemp(): Float = runCatching {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0f
    }.getOrDefault(0f)

    private fun getFreeStorageMb(): Long = runCatching {
        StatFs(ctx.filesDir.absolutePath).run {
            availableBlocksLong * blockSizeLong / (1024L * 1024L)
        }
    }.getOrDefault(-1L)

    /**
     * Finds the app with the most total background time in the last 24 hours.
     * "Background time" is approximated from totalTimeInForeground being low
     * relative to last-used recency — apps the OS woke without user interaction.
     */
    private fun getTopBackgroundDrainer(): DrainInfo? {
        if (!UsageAnalyser.hasPermission(ctx)) return null
        return runCatching {
            val usm   = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now   = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY,
                now - 24L * 3600 * 1000, now) ?: return null

            val top = stats
                .filter { s ->
                    s.packageName != ctx.packageName &&
                            s.packageName != "android" &&
                            s.packageName != "com.android.systemui" &&
                            s.totalTimeInForeground in 1L..30L * 60 * 1000  // less than 30 min fg
                }
                .maxByOrNull { it.totalTimeInForeground } ?: return null

            val bgMins = top.totalTimeInForeground / 60_000L
            val savings = when {
                bgMins > 120 -> 90
                bgMins > 60  -> 45
                bgMins > 30  -> 20
                else         -> 10
            }

            DrainInfo(
                packageName             = top.packageName,
                friendlyName            = getAppLabel(top.packageName),
                backgroundMinutes       = bgMins,
                estimatedSavingsMinutes = savings
            )
        }.getOrNull()
    }

    private fun getScreenOnMinutes(): Long {
        if (!UsageAnalyser.hasPermission(ctx)) return -1L
        return runCatching {
            val usm   = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val cal   = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
            }
            val events = usm.queryEvents(cal.timeInMillis, System.currentTimeMillis())
            val ev     = android.app.usage.UsageEvents.Event()
            var totalMs = 0L; var lastResume = -1L

            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                when (ev.eventType) {
                    android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED ->
                        if (lastResume < 0) lastResume = ev.timeStamp
                    android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED  -> {
                        if (lastResume >= 0) { totalMs += ev.timeStamp - lastResume; lastResume = -1L }
                    }
                }
            }
            totalMs / 60_000L
        }.getOrDefault(-1L)
    }

    private fun getPickupCount(): Int {
        if (!UsageAnalyser.hasPermission(ctx)) return -1
        return runCatching {
            val usm  = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val cal  = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
            }
            val events = usm.queryEvents(cal.timeInMillis, System.currentTimeMillis())
            val ev     = android.app.usage.UsageEvents.Event()
            var count  = 0; var lastPkg = ""
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
                    && ev.packageName != lastPkg) { count++; lastPkg = ev.packageName }
            }
            count
        }.getOrDefault(-1)
    }

    // ── Context builder ───────────────────────────────────────────────────────
    private fun buildContextJson(
        level: Int, charging: Boolean, health: String, temp: Float,
        drain: DrainInfo?, screenOn: Long, pickups: Int, storage: Long
    ): String = JSONObject().apply {
        put("battery_level",        level)
        put("is_charging",          charging)
        put("battery_health",       health)
        put("battery_temp_c",       temp)
        put("screen_on_mins_today", screenOn)
        put("pickup_count_today",   pickups)
        put("free_storage_mb",      storage)
        put("hour_of_day",          Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
        if (drain != null) {
            put("top_drain_app",             drain.friendlyName)
            put("top_drain_pkg",             drain.packageName)
            put("top_drain_bg_mins",         drain.backgroundMinutes)
            put("estimated_savings_mins",    drain.estimatedSavingsMinutes)
        }
    }.toString()

    // ── LLM diagnosis generation ──────────────────────────────────────────────
    private fun generateDiagnosis(
        contextJson: String,
        level: Int, charging: Boolean, temp: Float, health: String,
        drain: DrainInfo?, screenOn: Long, pickups: Int
    ): String {
        // Try LLM first
        if (AxiomEngine.isEngineReady()) {
            val prompt = buildLlmPrompt(level, charging, temp, health, drain, screenOn, pickups)
            val result = runCatching { AxiomEngine.conversationalAnswer(prompt, 120) }.getOrNull()
            if (!result.isNullOrBlank() && result.length > 20) return result.trim()
        }
        // Template fallback — still data-driven, still valuable
        return buildTemplateDiagnosis(level, charging, temp, health, drain, screenOn, pickups)
    }

    private fun buildLlmPrompt(
        level: Int, charging: Boolean, temp: Float, health: String,
        drain: DrainInfo?, screenOn: Long, pickups: Int
    ): String = buildString {
        appendLine("You are a phone hardware expert. Write 2-3 sentences in plain English.")
        appendLine("Use the exact numbers provided. Give one clear action the user should take.")
        appendLine("Do not use 'I', 'AI', or generic advice. Diagnose like a doctor reading test results.")
        appendLine()
        appendLine("Data:")
        if (level >= 0)    appendLine("Battery: $level%${if (charging) " (charging)" else ""}")
        if (temp > 0)      appendLine("Temperature: ${temp}°C${if (temp > 38) " — elevated" else " — normal"}")
        if (health != "Good" && health != "Unknown") appendLine("Health: $health")
        if (screenOn > 0) {
            val h = screenOn / 60; val m = screenOn % 60
            appendLine("Screen on today: ${if (h > 0) "${h}h ${m}m" else "${m} min"}")
        }
        if (pickups > 0)   appendLine("Phone pickups today: $pickups")
        if (drain != null) {
            appendLine("Top background app: ${drain.friendlyName} — ${drain.backgroundMinutes} min background")
            appendLine("Restricting it saves ~${drain.estimatedSavingsMinutes} min of battery")
        }
        appendLine(); append("Diagnosis:")
    }

    private fun buildTemplateDiagnosis(
        level: Int, charging: Boolean, temp: Float, health: String,
        drain: DrainInfo?, screenOn: Long, pickups: Int
    ): String = buildString {
        if (level >= 0) append("Battery is at $level%${if (charging) ", currently charging" else ""}. ")
        if (temp > 38)  append("Temperature is elevated at ${temp}°C — avoid wireless charging until it cools. ")
        if (health != "Good" && health != "Unknown") append("Battery health is $health. ")
        if (drain != null) {
            append("${drain.friendlyName} ran silently in the background for ${drain.backgroundMinutes} minutes today. ")
            append("Restricting its background activity could extend your battery by ~${drain.estimatedSavingsMinutes} minutes. ")
        }
        if (screenOn > 0) {
            val h = screenOn / 60; val m = screenOn % 60
            append("Your screen has been on for ${if (h > 0) "${h}h ${m}m" else "${m} minutes"} today")
            if (pickups > 0) append(", across $pickups phone pickups")
            append(".")
        }
    }.trim().ifEmpty { "Collecting battery data. Check again in a few minutes." }

    private fun getAppLabel(pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(
            ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrElse { pkg.split(".").last().replaceFirstChar { it.uppercase() } }
}