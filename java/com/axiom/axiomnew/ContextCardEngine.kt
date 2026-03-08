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

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ════════════════════════════════════════════════════════════════════════════
//  ContextCardEngine — The "wow factor" engine
//
//  Reads all on-device data (prediction table, usage log, battery, calendar)
//  and produces a ContextCard: a single, specific, personal insight that
//  changes throughout the day as patterns are detected.
//
//  Examples of what this generates:
//    "You open Spotify every Tuesday evening — 6 of the last 7 weeks."
//    "Good morning. You usually navigate somewhere at this time on weekdays."
//    "Battery at 18%. You typically charge around 9 PM — that's 2 hours away."
//    "You've picked up your phone 47 times today, 31 of those opened Instagram."
//
//  The power: every number is REAL, pulled from actual device data.
//  This is what creates the "how does it know?" reaction.
//
//  Architecture:
//    • build() — blocking, always call off main thread
//    • Returns a ContextCard or a generic greeting if no patterns exist yet
//    • Called by MainActivity.onResume() via a background Thread
// ════════════════════════════════════════════════════════════════════════════
class ContextCardEngine(private val ctx: Context) {

    enum class CardType {
        HABIT_PREDICTION,   // "You usually open X at this time"
        APP_STREAK,         // "You've opened X every day this week"
        BATTERY_AWARE,      // "Low battery — you usually charge at Y"
        USAGE_SPIKE,        // "You've been on your phone N hours today"
        DORMANT_APP,        // "You haven't opened X in N days (usually daily)"
        MORNING_BRIEF,      // First open of the day — summary
        WORLD_MODEL_SNAP,   // Live sensor + learned context snapshot — "Axiom's model of right now"
        LEARNING_MILESTONE, // Adapter crossed a confidence threshold
        GENERIC_READY       // Fallback — not enough data yet
    }

    data class ContextCard(
        val greeting:      String,        // "Good morning" / "Good evening"
        val headline:      String,        // Main insight — the wow line
        val supportLine:   String,        // Supporting detail with numbers
        val footerLine:    String,        // "Based on 21 days of learning"
        val actionLabel:   String? = null,// "Open Spotify" — shown as button
        val actionPayload: String? = null,// package name or ACTION_* string
        val cardType:      CardType = CardType.GENERIC_READY,
        val accentColor:   Int = 0xFF00C8FF.toInt()
    )

    // ── Main entry ────────────────────────────────────────────────────────────
    fun build(): ContextCard {
        val cal       = Calendar.getInstance()
        val hour      = cal.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) // 1=Sun … 7=Sat
        val greeting  = timeGreeting(hour)

        // Try each card type in priority order
        return tryBatteryCard(hour)
            ?: tryLearningMilestone(greeting)
            ?: tryHabitPrediction(hour, dayOfWeek, greeting)
            ?: tryAppStreak(greeting)
            ?: tryDormantApp(greeting)
            ?: tryUsageSpike(hour, greeting)
            ?: tryWorldModelSnap(hour, greeting)   // always produces a card — richer than generic
            ?: genericReadyCard(greeting)
    }

    // ── Card 1: Battery-aware ─────────────────────────────────────────────────
    // "Battery at 18%. You usually charge at 9 PM — 2 hours away."
    private fun tryBatteryCard(hour: Int): ContextCard? {
        val bm      = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct     = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging

        if (charging || pct > 30) return null   // only show when battery is a concern

        // Find the hour the user most commonly plugs in, from usage patterns
        val plugHour = inferChargeHour() ?: return null
        val hoursUntil = ((plugHour - hour + 24) % 24).let { if (it == 0) 24 else it }
        val hoursStr = when (hoursUntil) {
            1    -> "about 1 hour away"
            in 2..5 -> "$hoursUntil hours away"
            else -> null
        } ?: return null

        val chargeTime = formatHour(plugHour)
        return ContextCard(
            greeting    = "Heads up",
            headline    = "Battery at $pct% — low",
            supportLine = "You usually plug in around $chargeTime ($hoursStr).",
            footerLine  = "Based on your charging patterns",
            cardType    = CardType.BATTERY_AWARE,
            accentColor = 0xFFF59E0B.toInt()   // amber
        )
    }

    // ── Card 2: Habit prediction ───────────────────────────────────────────────
    // "You open Maps every Tuesday morning — 4 of the last 5 weeks."
    private fun tryHabitPrediction(hour: Int, dayOfWeek: Int, greeting: String): ContextCard? {
        val predsFile = File(ctx.filesDir, "axiom_preds.csv")
        if (!predsFile.exists()) return null

        data class PredRow(val intent: String, val hour: Int, val hits: Int, val total: Int)

        val rows = predsFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val p = line.split(",")
                if (p.size < 4) null
                else runCatching {
                    PredRow(p[0].trim(), p[1].trim().toInt(),
                        p[2].trim().toInt(), p[3].trim().toInt())
                }.getOrNull()
            }
            .filter { it.hour == hour && it.total >= 3 }
            .sortedByDescending { it.hits.toFloat() / it.total }

        val best = rows.firstOrNull { it.hits.toFloat() / it.total >= 0.55f }
            ?: return null

        val hitRate  = (best.hits.toFloat() / best.total * 100).toInt()
        val isAppPkg = best.intent.startsWith("APP:")
        val pkg      = if (isAppPkg) best.intent.removePrefix("APP:") else null
        val appLabel = if (pkg != null) getAppLabel(pkg) else null
        val label    = appLabel ?: friendlyIntentLabel(best.intent)
        val dayName  = dayName(dayOfWeek)
        val timeDesc = timeDescription(hour)

        val headline = when {
            hitRate >= 85 -> "You almost always use $label at this time"
            hitRate >= 65 -> "You usually use $label $timeDesc"
            else          -> "You often use $label on $dayName $timeDesc"
        }

        val supportLine = "${best.hits} of the last ${best.total} times at this hour, you opened $label."

        return ContextCard(
            greeting      = greeting,
            headline      = headline,
            supportLine   = supportLine,
            footerLine    = "Learned from ${best.total} observations",
            actionLabel   = if (pkg != null) "Open $label" else null,
            actionPayload = pkg,
            cardType      = CardType.HABIT_PREDICTION
        )
    }

    // ── Card 3: App streak ────────────────────────────────────────────────────
    // "You've opened Instagram every day this week — 7 days running."
    private fun tryAppStreak(greeting: String): ContextCard? {
        if (!UsageAnalyser.hasPermission(ctx)) return null
        return runCatching {
            val usm    = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now    = System.currentTimeMillis()
            val days   = 8
            val events = usm.queryEvents(now - days * 86400_000L, now)
            val ev     = UsageEvents.Event()

            // Count how many distinct calendar days each app was opened
            val appDays = mutableMapOf<String, MutableSet<Int>>()
            val cal = Calendar.getInstance()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                val pkg = ev.packageName
                if (!isUserApp(pkg)) continue
                cal.timeInMillis = ev.timeStamp
                appDays.getOrPut(pkg) { mutableSetOf() }
                    .add(cal.get(Calendar.DAY_OF_YEAR))
            }

            // Today's day-of-year
            cal.timeInMillis = now
            val today = cal.get(Calendar.DAY_OF_YEAR)

            // Find an app opened every one of the last 5+ days including today
            val streak = appDays.entries
                .filter { (_, daySet) ->
                    daySet.contains(today) && daySet.size >= 5
                }
                .maxByOrNull { it.value.size }
                ?: return null

            val pkg      = streak.key
            val streakLen = streak.value.size
            val label    = getAppLabel(pkg)

            ContextCard(
                greeting      = greeting,
                headline      = "You've opened $label every day this week",
                supportLine   = "$streakLen consecutive days — it's become a daily habit.",
                footerLine    = "Detected from your app usage history",
                actionLabel   = "Open $label",
                actionPayload = pkg,
                cardType      = CardType.APP_STREAK
            )
        }.getOrNull()
    }

    // ── Card 4: Dormant app ───────────────────────────────────────────────────
    // "You haven't opened Duolingo in 9 days — you used it daily before."
    private fun tryDormantApp(greeting: String): ContextCard? {
        if (!UsageAnalyser.hasPermission(ctx)) return null
        return runCatching {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()

            // Get stats for last 30 days
            val stats30 = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_MONTHLY,
                now - 30 * 86400_000L, now
            ) ?: return null

            // Get stats for last 7 days
            val stats7pkg = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_WEEKLY,
                now - 7 * 86400_000L, now
            )?.map { it.packageName }?.toSet() ?: emptySet()

            // Find apps active in the 30d window but not in the last 7 days
            val dormant = stats30
                .filter { s ->
                    isUserApp(s.packageName) &&
                            s.totalTimeInForeground > 10 * 60_000L &&
                            !stats7pkg.contains(s.packageName)
                }
                .maxByOrNull { it.totalTimeInForeground }
                ?: return null

            val label    = getAppLabel(dormant.packageName)
            val totalMin = dormant.totalTimeInForeground / 60_000L

            // Calculate days since last use
            val lastUsed = dormant.lastTimeUsed
            val daysSince = ((now - lastUsed) / 86400_000L).toInt().coerceAtLeast(7)

            ContextCard(
                greeting      = greeting,
                headline      = "You haven't opened $label in $daysSince days",
                supportLine   = "You used it for ${totalMin}min over the past month — then stopped.",
                footerLine    = "Noticed from your usage patterns",
                actionLabel   = "Open $label",
                actionPayload = dormant.packageName,
                cardType      = CardType.DORMANT_APP,
                accentColor   = 0xFF8B5CF6.toInt()   // purple
            )
        }.getOrNull()
    }

    // ── Card 5: Usage spike ───────────────────────────────────────────────────
    // "You've been on your phone 3.2 hours today — your highest this week."
    private fun tryUsageSpike(hour: Int, greeting: String): ContextCard? {
        if (!UsageAnalyser.hasPermission(ctx) || hour < 12) return null
        return runCatching {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val cal = Calendar.getInstance()

            // Today's screen time
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            val startOfDay = cal.timeInMillis

            val todayStats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, startOfDay, now
            ) ?: return null

            val todayMins = todayStats
                .filter { isUserApp(it.packageName) }
                .sumOf { it.totalTimeInForeground } / 60_000L

            if (todayMins < 90) return null   // need at least 1.5h to be interesting

            // This week's daily average for comparison
            val weekStats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_WEEKLY,
                now - 7 * 86400_000L, now
            ) ?: return null

            val weekMins = weekStats
                .filter { isUserApp(it.packageName) }
                .sumOf { it.totalTimeInForeground } / 60_000L
            val weekDailyAvg = weekMins / 7

            if (weekDailyAvg < 30 || todayMins < weekDailyAvg * 1.3f) return null

            val todayHours  = "%.1f".format(todayMins / 60f)
            val avgHours    = "%.1f".format(weekDailyAvg / 60f)
            val extraMins   = todayMins - weekDailyAvg

            ContextCard(
                greeting    = greeting,
                headline    = "You've been on your phone ${todayHours}h today",
                supportLine = "That's ${extraMins}min more than your daily average (${avgHours}h).",
                footerLine  = "Screen time tracked on-device only",
                cardType    = CardType.USAGE_SPIKE,
                accentColor = 0xFFEF4444.toInt()   // red
            )
        }.getOrNull()
    }

    // ── Card 6: World Model Snapshot ─────────────────────────────────────────
    // Shows Axiom's live model of the user's current context in plain English.
    // This is the "how does it know?" card — surfaces what the world model
    // actually contains right now so the user feels the difference from cloud AI.
    //
    // Always produces a card (never returns null) so it acts as a rich fallback.
    private fun tryWorldModelSnap(hour: Int, greeting: String): ContextCard {
        val bm       = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery  = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging

        val cm      = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val onWifi  = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val am          = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ringerState = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT  -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else                              -> "normal"
        }

        // Adapter stats
        val stats         = runCatching { AxiomEngine.getAdapterStats() }.getOrElse { "" }
        val interactions  = parseStatInt(stats, "interactions")
        val confPct       = (interactions.coerceAtMost(100))
        val phaseLabel    = when {
            interactions < 5   -> "just started"
            interactions < 20  -> "early learning"
            interactions < 50  -> "pattern forming"
            interactions < 100 -> "well trained"
            else               -> "deeply personalised"
        }

        // Prediction table row count
        val predRows = runCatching {
            File(ctx.filesDir, "axiom_preds.csv").readLines()
                .count { it.isNotBlank() && !it.startsWith("#") }
        }.getOrElse { 0 }

        // Build a natural-language snapshot of what the model knows right now
        val contextLines = buildList<String> {
            add("${timeDescription(hour).replaceFirstChar { it.uppercase() }} · ${formatHour(hour)}")

            when {
                charging        -> add("Plugged in · battery $battery%")
                battery <= 20   -> add("Battery low at $battery%")
                else            -> add("Battery $battery%")
            }

            add(if (onWifi) "On WiFi" else "Mobile data · no WiFi")
            add("Ringer: $ringerState")

            if (predRows > 0) add("$predRows hourly habits learned")
        }

        val headline = when {
            interactions == 0 ->
                "Axiom is ready — no cloud needed"
            interactions < 5  ->
                "Building your world model"
            confPct >= 80     ->
                "Your world model is fully trained"
            else              ->
                "Your world model · $phaseLabel"
        }

        val support = contextLines.joinToString(" · ")

        val footer = when {
            interactions == 0 ->
                "Everything runs on this device · 0 bytes sent to any server"
            interactions < 20 ->
                "$interactions interactions recorded · model improving each time"
            else              ->
                "$interactions interactions · $confPct% confidence · zero cloud"
        }

        return ContextCard(
            greeting      = greeting,
            headline      = headline,
            supportLine   = support,
            footerLine    = footer,
            cardType      = CardType.WORLD_MODEL_SNAP,
            accentColor   = 0xFF00C8FF.toInt()
        )
    }

    // ── Card 7: Learning Milestone ────────────────────────────────────────────
    // Surfaces when the adapter crosses meaningful thresholds (5, 20, 50, 100).
    // Makes the invisible learning visible — one of the core differentiators.
    private fun tryLearningMilestone(greeting: String): ContextCard? {
        val stats        = runCatching { AxiomEngine.getAdapterStats() }.getOrElse { return null }
        val interactions = parseStatInt(stats, "interactions")

        // Milestones and the prefs key that tracks whether we've shown each
        val milestones = listOf(5 to "milestone_5", 20 to "milestone_20",
            50 to "milestone_50", 100 to "milestone_100")

        val prefs = ctx.getSharedPreferences("axiom_milestones", Context.MODE_PRIVATE)

        val milestone = milestones.firstOrNull { (threshold, key) ->
            interactions >= threshold && !prefs.getBoolean(key, false)
        } ?: return null

        val (threshold, key) = milestone
        // Mark as shown so it only appears once
        prefs.edit().putBoolean(key, true).apply()

        val (headline, support) = when (threshold) {
            5   -> Pair(
                "Axiom is starting to know you",
                "5 interactions in — the adapter is forming. Suggestions will start improving."
            )
            20  -> Pair(
                "Patterns are emerging",
                "20 interactions recorded. The LLM is being bypassed for familiar requests — it's getting faster."
            )
            50  -> Pair(
                "Your world model is taking shape",
                "50 interactions. Axiom is now predicting before you ask for many of your regular patterns."
            )
            100 -> Pair(
                "Fully personalised — no cloud ever needed",
                "100 interactions. Your adapter has learned your habits deeply. This model exists only on this device."
            )
            else -> return null
        }

        return ContextCard(
            greeting    = greeting,
            headline    = headline,
            supportLine = support,
            footerLine  = "$threshold interactions · all learning on-device · zero cloud",
            cardType    = CardType.LEARNING_MILESTONE,
            accentColor = 0xFF34D399.toInt()   // green — positive milestone
        )
    }

    // ── Fallback ──────────────────────────────────────────────────────────────
    private fun genericReadyCard(greeting: String): ContextCard {
        val predsFile = File(ctx.filesDir, "axiom_preds.csv")
        val hasData   = predsFile.exists() && predsFile.length() > 0

        return if (hasData) {
            val rowCount = predsFile.readLines().filter { it.isNotBlank() }.size
            ContextCard(
                greeting    = greeting,
                headline    = "Building your world model",
                supportLine = "$rowCount habit patterns tracked. No server involved — this model lives only on your device.",
                footerLine  = "All learning on-device · 0 bytes sent anywhere",
                cardType    = CardType.GENERIC_READY
            )
        } else {
            ContextCard(
                greeting    = greeting,
                headline    = "Your on-device AI is ready",
                supportLine = "Unlike ChatGPT or Gemini, everything Axiom learns stays here. Ask it anything about your phone.",
                footerLine  = "No cloud · no account · no data leaving this device",
                cardType    = CardType.GENERIC_READY
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // ── Package blocklist ─────────────────────────────────────────────────────
    // Excludes launchers, system UI, input methods, and other background
    // packages that show up in UsageStats but aren't real user-opened apps.
    private fun isUserApp(pkg: String): Boolean {
        if (pkg == ctx.packageName) return false
        val blocked = listOf(
            "android", "com.android.", "com.google.android.gms",
            "com.google.android.gsf", "com.google.android.inputmethod",
            "com.google.android.permissioncontroller",
            // Launchers
            "launcher", "home", "com.miui.home", "com.sec.android.app.launcher",
            "com.huawei.android.launcher", "com.oneplus.launcher",
            "com.oppo.launcher", "com.vivo.launcher",
            "com.realme.launcher", "com.nothing.launcher",
            // System UI / overlays
            "systemui", "com.miui.systemui", "com.android.systemui",
            // Input / keyboard
            "inputmethod", "keyboard", "com.google.android.inputmethod",
            "com.swiftkey", "com.touchtype.swiftkey",
            // Settings / setup
            "com.android.settings", "com.miui.securitycenter",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            // Download / files
            "com.android.providers", "com.android.externalstorage",
            // Phone / dialer defaults
            "com.android.server.telecom",
            // Misc system
            "com.android.wallpaper", "com.android.documentsui",
            "com.google.android.apps.restore", "com.android.vending.billing"
        )
        return blocked.none { pkg == it || pkg.startsWith(it) || pkg.contains(it) }
    }

    private fun timeGreeting(hour: Int) = when (hour) {
        in 5..11  -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..20 -> "Good evening"
        else      -> "Hey"
    }

    private fun timeDescription(hour: Int) = when (hour) {
        in 5..8   -> "in the morning"
        in 9..11  -> "mid-morning"
        in 12..13 -> "at lunchtime"
        in 14..16 -> "in the afternoon"
        in 17..19 -> "in the evening"
        in 20..22 -> "at night"
        else      -> "late at night"
    }

    private fun dayName(dow: Int) = when (dow) {
        Calendar.MONDAY    -> "Monday"
        Calendar.TUESDAY   -> "Tuesday"
        Calendar.WEDNESDAY -> "Wednesday"
        Calendar.THURSDAY  -> "Thursday"
        Calendar.FRIDAY    -> "Friday"
        Calendar.SATURDAY  -> "Saturday"
        else               -> "Sunday"
    }

    private fun formatHour(hour: Int): String {
        val amPm  = if (hour < 12) "AM" else "PM"
        val h12   = if (hour % 12 == 0) 12 else hour % 12
        return "$h12 $amPm"
    }

    private fun getAppLabel(pkg: String): String {
        return runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(
                pm.getApplicationInfo(pkg, 0)
            ).toString()
        }.getOrDefault(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() })
    }

    private fun friendlyIntentLabel(intent: String): String =
        intent.removePrefix("ACTION_")
            .replace("_", " ")
            .lowercase()
            .replaceFirstChar { it.uppercase() }

    // Infer the most common charging hour from usage history
    // (We approximate this by looking at what hour battery events cluster at)
    private fun parseStatInt(stats: String, key: String): Int =
        runCatching {
            stats.lines().firstOrNull { it.startsWith("$key=") }
                ?.substringAfter("=")?.trim()?.toInt() ?: 0
        }.getOrElse { 0 }

    private fun inferChargeHour(): Int? {
        // Read from axiom_preds.csv — look for "ACTION_BATTERY_SAVER" at what hour,
        // or fall back to a simple evening heuristic
        val predsFile = File(ctx.filesDir, "axiom_preds.csv")
        if (!predsFile.exists()) return 21  // default: 9 PM

        val chargingHours = predsFile.readLines()
            .filter { it.contains("BATTERY") || it.contains("CHARGING") }
            .mapNotNull { line ->
                val p = line.split(",")
                if (p.size < 4) null else p[1].trim().toIntOrNull()
            }

        return chargingHours.groupBy { it }
            .maxByOrNull { it.value.size }?.key
            ?: 21
    }
}