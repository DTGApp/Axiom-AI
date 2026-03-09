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

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.BatteryManager
import org.json.JSONObject
import java.util.Calendar

// ════════════════════════════════════════════════════════════════════════════════
//  InsightEngine  —  Feature 2: Silent Pattern Narrator
//
//  Analyses 7 days of usage behaviour and generates 1 surprising, specific
//  personal insight the user has never been told. Sent as a weekly notification.
//
//  The surprise factor comes from surfacing patterns that:
//    • Are real (drawn from actual usage data)
//    • Are specific (exact numbers, exact times)
//    • The user didn't know their phone was tracking
//
//  Examples of what this generates:
//    "You picked up your phone 94 times on Tuesday, mostly 2–4 PM.
//     Every time, you opened Instagram. On those days, your screen-on
//     time runs 40 minutes longer than other days."
//
//    "You haven't opened your fitness app in 11 days, but you open
//     YouTube every day at 7 AM — usually for 23 minutes."
//
//  Architecture:
//    • InsightEngine.generate() — blocking, call off main thread
//    • Returns InsightReport with an LLM-generated narrative
//    • AxiomService calls this weekly, sends result as notification
//    • Saves last insight to prefs so it is never repeated
// ════════════════════════════════════════════════════════════════════════════════
class InsightEngine(private val ctx: Context) {

    companion object {
        const val PREFS_NAME        = "axiom_insights"
        const val KEY_LAST_INSIGHT  = "last_insight_ts"
        const val KEY_LAST_TEXT     = "last_insight_text"
        const val INSIGHT_GAP_MS    = 6L * 24 * 3600 * 1000  // minimum 6 days between insights
    }

    data class AppUsageSlot(
        val packageName:  String,
        val friendlyName: String,
        val totalMins:    Long,
        val peakHour:     Int,
        val dailyAvgMins: Long,
        val openCount:    Int,
        val daysActive:   Int
    )

    data class InsightReport(
        val narrative:   String,      // LLM or template generated insight
        val dataPoints:  List<String>,// supporting facts for UI display
        val insightType: InsightType
    )

    enum class InsightType {
        PEAK_HABIT,        // you always open X at time Y
        HIDDEN_DRAINER,    // app running without you knowing
        STREAK_BREAK,      // app you stopped using
        VOLUME_SURPRISE,   // more/less than you think
        CORRELATION        // two behaviours linked
    }

    // ── Main entry point ──────────────────────────────────────────────────────
    fun generate(): InsightReport? {
        if (!UsageAnalyser.hasPermission(ctx)) return null

        val slots  = collectAppSlots(days = 7)
        if (slots.isEmpty()) return null

        val insight = chooseInsight(slots) ?: return null
        return insight
    }

    fun isDue(): Boolean {
        val prefs   = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastTs  = prefs.getLong(KEY_LAST_INSIGHT, 0L)
        return System.currentTimeMillis() - lastTs > INSIGHT_GAP_MS
    }

    fun markSent(text: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_INSIGHT, System.currentTimeMillis())
            .putString(KEY_LAST_TEXT, text)
            .apply()
    }

    fun getLastInsight(): String? =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_TEXT, null)

    // ── Usage data collection ─────────────────────────────────────────────────
    private fun collectAppSlots(days: Int): List<AppUsageSlot> {
        return runCatching {
            val usm   = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now   = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_WEEKLY,
                now - days * 24L * 3600 * 1000, now
            ) ?: return emptyList()

            // Per-hour open counts to find peak hour
            val hourEvents = collectHourlyEvents(days)

            // Build set of packages that have a launcher icon — the only ones
            // users actually "open". Excludes system UI, settings, launchers,
            // digital wellbeing, and all background services.
            val launchablePackages = runCatching {
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                ctx.packageManager.queryIntentActivities(intent, 0)
                    .map { it.activityInfo.packageName }.toSet()
            }.getOrElse { emptySet() }

            stats
                .filter { s ->
                    val pkg = s.packageName
                    pkg != ctx.packageName &&
                            s.totalTimeInForeground > 2L * 60 * 1000 &&
                            launchablePackages.contains(pkg) &&
                            !isSystemPackage(pkg)
                }
                .map { s ->
                    val pkg       = s.packageName
                    val totalMins = s.totalTimeInForeground / 60_000L
                    val peakHour  = hourEvents[pkg]?.maxByOrNull { it.value }?.key ?: -1
                    val opens     = hourEvents[pkg]?.values?.sum() ?: 0
                    val daysActive= estimateDaysActive(s, days)

                    AppUsageSlot(
                        packageName  = pkg,
                        friendlyName = getAppLabel(pkg),
                        totalMins    = totalMins,
                        peakHour     = peakHour,
                        dailyAvgMins = totalMins / days.coerceAtLeast(1),
                        openCount    = opens,
                        daysActive   = daysActive
                    )
                }
                .sortedByDescending { it.totalMins }
        }.getOrDefault(emptyList())
    }

    private fun collectHourlyEvents(days: Int): Map<String, MutableMap<Int, Int>> {
        return runCatching {
            val usm    = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now    = System.currentTimeMillis()
            val events = usm.queryEvents(now - days * 24L * 3600 * 1000, now)
            val ev     = android.app.usage.UsageEvents.Event()
            val result = mutableMapOf<String, MutableMap<Int, Int>>()

            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (isSystemPackage(ev.packageName)) continue
                    val cal  = Calendar.getInstance().apply { timeInMillis = ev.timeStamp }
                    val hour = cal.get(Calendar.HOUR_OF_DAY)
                    result.getOrPut(ev.packageName) { mutableMapOf() }
                        .merge(hour, 1, Int::plus)
                }
            }
            result
        }.getOrDefault(emptyMap())
    }

    private fun estimateDaysActive(stats: android.app.usage.UsageStats, maxDays: Int): Int {
        // Heuristic: if average daily time > 1 min, count as active
        val avgMs = stats.totalTimeInForeground / maxDays.toDouble()
        return if (avgMs > 60_000) maxDays else (stats.totalTimeInForeground / 300_000L).toInt().coerceIn(1, maxDays)
    }

    // ── Insight selection ─────────────────────────────────────────────────────
    private fun chooseInsight(slots: List<AppUsageSlot>): InsightReport? {
        // Pick the most interesting insight type available in order of surprise value

        // 1. Correlation: most-used app at a specific time of day
        val peakHabit = slots.filter { it.peakHour >= 0 && it.openCount >= 5 }
            .maxByOrNull { it.openCount }
        if (peakHabit != null) {
            return buildPeakHabitInsight(peakHabit, slots)
        }

        // 2. Volume surprise: most used app overall
        val topApp = slots.firstOrNull()
        if (topApp != null && topApp.totalMins > 30) {
            return buildVolumeInsight(topApp, slots)
        }

        return null
    }

    private fun buildPeakHabitInsight(peak: AppUsageSlot, allSlots: List<AppUsageSlot>): InsightReport {
        val hourLabel = formatHour(peak.peakHour)
        val totalHrs  = peak.totalMins / 60
        val totalMins = peak.totalMins % 60

        val dataPoints = buildList {
            add("${peak.friendlyName}: ${peak.openCount} opens in 7 days")
            add("Most active: $hourLabel daily")
            add("Total time: ${if (totalHrs > 0) "${totalHrs}h ${totalMins}m" else "${peak.totalMins} min"}")
            if (peak.daysActive >= 5) add("Active ${peak.daysActive} of 7 days — a strong habit")
        }

        val narrative = generateNarrative(InsightType.PEAK_HABIT, dataPoints, peak, null)
        return InsightReport(narrative, dataPoints, InsightType.PEAK_HABIT)
    }

    private fun buildVolumeInsight(top: AppUsageSlot, allSlots: List<AppUsageSlot>): InsightReport {
        val totalHrs  = top.totalMins / 60
        val totalMins = top.totalMins % 60
        val second    = allSlots.getOrNull(1)

        val dataPoints = buildList {
            add("${top.friendlyName}: ${if (totalHrs > 0) "${totalHrs}h ${totalMins}m" else "${top.totalMins} min"} this week")
            add("Daily average: ${top.dailyAvgMins} minutes")
            if (second != null) add("2nd most used: ${second.friendlyName} (${second.totalMins} min)")
        }

        val narrative = generateNarrative(InsightType.VOLUME_SURPRISE, dataPoints, top, second)
        return InsightReport(narrative, dataPoints, InsightType.VOLUME_SURPRISE)
    }

    // ── Narrative generation ──────────────────────────────────────────────────
    private fun generateNarrative(
        type: InsightType, dataPoints: List<String>,
        primary: AppUsageSlot, secondary: AppUsageSlot?
    ): String {
        if (AxiomEngine.isEngineReady()) {
            val prompt = buildNarrativePrompt(type, dataPoints, primary, secondary)
            val result = runCatching { AxiomEngine.conversationalAnswer(prompt) }.getOrNull()
            if (!result.isNullOrBlank() && result.length > 20) return result.trim()
        }
        return buildTemplateNarrative(type, primary, secondary, dataPoints)
    }

    private fun buildNarrativePrompt(
        type: InsightType, dataPoints: List<String>,
        primary: AppUsageSlot, secondary: AppUsageSlot?
    ): String = buildString {
        appendLine("Write one surprising, specific insight about this person's phone habits in 2 sentences.")
        appendLine("Use exact numbers. Sound like a friend who just noticed something interesting, not a system message.")
        appendLine("Do not use 'I', 'your data shows', or generic phrasing.")
        appendLine()
        appendLine("Facts:")
        dataPoints.forEach { appendLine("- $it") }
        if (primary.peakHour >= 0)
            appendLine("- Peak usage time: ${formatHour(primary.peakHour)}")
        appendLine(); append("Insight:")
    }

    private fun buildTemplateNarrative(
        type: InsightType, primary: AppUsageSlot,
        secondary: AppUsageSlot?, dataPoints: List<String>
    ): String = when (type) {
        InsightType.PEAK_HABIT -> {
            val hourLabel = if (primary.peakHour >= 0) formatHour(primary.peakHour) else "daily"
            "${primary.friendlyName} has been opened ${primary.openCount} times in the last 7 days, " +
                    "mostly around $hourLabel. That's a consistent habit — ${primary.dailyAvgMins} minutes a day on average."
        }
        InsightType.VOLUME_SURPRISE -> {
            val hrs = primary.totalMins / 60; val mins = primary.totalMins % 60
            val time = if (hrs > 0) "${hrs}h ${mins}m" else "${primary.totalMins} minutes"
            "${primary.friendlyName} was open for $time this week — about ${primary.dailyAvgMins} minutes a day. " +
                    if (secondary != null) "That's ${primary.totalMins / secondary.totalMins.coerceAtLeast(1)}× more than ${secondary.friendlyName}."
                    else "That's your most-used app this week."
        }
        else -> dataPoints.firstOrNull() ?: "New patterns detected in your usage."
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun formatHour(hour: Int): String = when {
        hour == 0  -> "midnight"
        hour < 12  -> "$hour AM"
        hour == 12 -> "noon"
        else       -> "${hour - 12} PM"
    }

    private fun isSystemPackage(pkg: String): Boolean {
        if (pkg == "android") return true
        val systemPrefixes = listOf(
            "com.android.", "android.", "com.google.android.gms",
            "com.google.android.gsf", "com.google.android.inputmethod",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.google.android.apps.restore",
            "com.miui.securitycenter", "com.miui.systemui", "com.miui.home",
            "com.sec.android.app.launcher", "com.huawei.android.launcher",
            "com.oneplus.launcher", "com.oppo.launcher", "com.vivo.launcher",
            "com.realme.launcher", "com.nothing.launcher",
            "com.android.systemui", "com.android.settings",
            "com.android.launcher", "com.google.android.launcher",
            "com.android.providers", "com.android.externalstorage",
            "com.android.server.telecom", "com.android.wallpaper",
            "com.android.documentsui", "com.android.packageinstaller"
        )
        if (systemPrefixes.any { pkg.startsWith(it) || pkg == it }) return true
        val systemKeywords = listOf(
            "launcher", "systemui", "inputmethod", "keyboard",
            "wellbeing", "screentime", "setup", "installer",
            "permissioncontroller"
        )
        return systemKeywords.any { pkg.contains(it, ignoreCase = true) }
    }

    private fun getAppLabel(pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(
            ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrElse { pkg.split(".").last().replaceFirstChar { it.uppercase() } }
}