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

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

// ════════════════════════════════════════════════════════════════════════════════
//  UsageAnalyser
//
//  Reads Android UsageStatsManager to observe what the user actually does on
//  their phone — which apps they open, at what hours, on what days.
//
//  Axiom maps this real behaviour to intents and pre-populates:
//    • axiom_preds.csv   — temporal prediction table (hit rates per hour)
//    • axiom_adapter.bin — personal adapter gets warm-start training events
//    • axiom_usage.json  — human-readable event log for the Event Viewer UI
//
//  This means Axiom starts learning from DAY ONE without the user having to
//  interact with the suggestion system at all. By the time the user first
//  uses Axiom, it already knows their habits.
//
//  PERMISSION REQUIRED: android.permission.PACKAGE_USAGE_STATS
//  This is a special permission — user must grant it manually in Settings >
//  Apps > Special App Access > Usage Access. We cannot request it via the
//  normal runtime permission flow.
// ════════════════════════════════════════════════════════════════════════════════
object UsageAnalyser {

    // ── Package → direct launch package ──────────────────────────────────────
    // When Axiom detects the user opens e.g. Spotify every evening, it will
    // suggest launching Spotify directly — not just "open Sound Settings".
    // This map says: for this intent, try to launch these packages in order.
    // MainActivity.intentFor() reads this via getPreferredLaunchPackages().
    val INTENT_LAUNCH_PACKAGES: Map<String, List<String>> = mapOf(
        // Sound / Music — open the actual music app
        "ACTION_SOUND_SETTINGS"    to listOf("com.spotify.music", "com.google.android.music",
            "com.apple.android.music", "com.amazon.mp3",
            "com.soundcloud.android", "com.miui.player",
            "com.sec.android.app.music"),
        "ACTION_MEDIA_VOLUME"      to listOf("com.spotify.music", "com.google.android.music",
            "com.apple.android.music", "com.miui.player"),
        "ACTION_EQUALIZER"         to listOf("com.spotify.music", "com.dolby.dax",
            "com.sec.android.app.music"),
        // Location — open Maps
        "ACTION_LOCATION_SETTINGS" to listOf("com.google.android.apps.maps", "com.waze",
            "com.here.app.maps", "com.ubercab",
            "com.grabtaxi.passenger"),
        // Camera — open the camera app
        "ACTION_CAMERA_SETTINGS"   to listOf("com.google.android.GoogleCamera",
            "com.sec.android.app.camera",
            "com.miui.camera", "com.oneplus.camera"),
        "ACTION_PHOTO_QUALITY"     to listOf("com.google.android.GoogleCamera",
            "com.sec.android.app.camera", "com.miui.camera"),
        "ACTION_VIDEO_SETTINGS"    to listOf("com.google.android.GoogleCamera",
            "com.sec.android.app.camera", "com.miui.camera"),
        // Gallery
        "ACTION_GALLERY"           to listOf("com.google.android.apps.photos",
            "com.miui.gallery", "com.sec.android.gallery3d"),
        // VPN
        "ACTION_VPN"               to listOf("com.nordvpn.android", "com.expressvpn.vpn",
            "com.protonvpn.android", "com.privateinternetaccess.android"),
        // Messaging / Privacy
        "ACTION_PRIVACY_SETTINGS"  to listOf("org.thoughtcrime.securesms",
            "org.telegram.messenger", "com.whatsapp"),
        // Focus / DND
        "ACTION_FOCUS_MODE"        to listOf("com.forestapp.Forest", "com.onetouchapp.one"),
        "ACTION_DND"               to listOf("com.forestapp.Forest", "com.onetouchapp.one"),
        // Sleep
        "ACTION_SLEEP_SETTINGS"    to listOf("com.urbandroid.sleep", "com.relaxio.calmsleep"),
        // Stress / mindfulness
        "ACTION_STRESS_MONITOR"    to listOf("com.calm.android", "com.headspace.android",
            "com.endel.endel"),
        // Health
        "ACTION_HEALTH_SETTINGS"   to listOf("com.google.android.apps.fitness",
            "com.samsung.shealth", "com.xiaomi.hm.health"),
        "ACTION_STEPS_COUNTER"     to listOf("com.google.android.apps.fitness",
            "com.fitbit.FitbitMobile", "com.garmin.android.apps.connectmobile",
            "com.strava", "com.nike.plusgps"),
        // Game mode
        "ACTION_GAME_MODE"         to listOf("com.samsung.android.game.gamehome",
            "com.miui.gamebooster", "com.android.gamingmode"),
        // Digital wellbeing
        "ACTION_DIGITAL_WELLBEING" to listOf("com.google.android.apps.wellbeing",
            "com.samsung.android.forest"),
        // Smart home
        "ACTION_SMART_HOME"        to listOf("io.homeassistant.companion.android",
            "com.amazon.echo", "com.xiaomi.smarthome",
            "com.philips.lighting.hue", "com.tuya.smart"),
        // Routines
        "ACTION_ROUTINES"          to listOf("com.samsung.android.app.routines",
            "net.dinglisch.android.taskerm"),
        // Security / 2FA
        "ACTION_TWO_FACTOR_AUTH"   to listOf("com.google.android.apps.authenticator2",
            "com.authy.authy"),
        "ACTION_SECURITY_SETTINGS" to listOf("com.bitwarden.mobile", "com.onepassword.android"),
        // Cast
        "ACTION_CAST"              to listOf("com.google.android.apps.chromecast.app",
            "com.sec.android.smartmirroring"),
        // Productivity / Calendar
        "ACTION_CALENDAR_SETTINGS" to listOf("com.google.android.calendar",
            "com.microsoft.office.outlook"),
        "ACTION_DATE_TIME"         to listOf("com.google.android.calendar",
            "com.todoist.android.Todoist", "com.ticktick.task"),
        // Notifications
        "ACTION_NOTIFICATION_SETTINGS" to listOf("com.microsoft.teams",
            "com.slack", "com.discord"),
        // Bluetooth headphones
        "ACTION_BLUETOOTH_SETTINGS" to listOf("com.samsung.android.app.galaxybuds",
            "com.sony.songpal.mdrconnect",
            "com.bose.bosemusic"),
    )

    // ── Package → Axiom intent mapping ───────────────────────────────────────
    // Maps Android package names to the Axiom intent they indicate user intent for.
    // When the user opens Settings > WiFi, Axiom learns they care about WiFi at
    // that hour. When they open a music app, Axiom learns about sound settings.
    private val PACKAGE_TO_INTENT = mapOf(
        // Settings screens — direct mapping
        "com.android.settings"                  to "ACTION_DISPLAY_SETTINGS",  // generic settings open

        // Battery / Power
        "com.miui.powercenter"                  to "ACTION_BATTERY_SAVER",
        "com.android.settings.battery"          to "ACTION_BATTERY_SAVER",
        "com.samsung.android.sm.battery"        to "ACTION_BATTERY_SAVER",

        // Storage / Files
        "com.android.documentsui"               to "ACTION_MANAGE_STORAGE",
        "com.mi.android.globalFileexplorer"     to "ACTION_MANAGE_STORAGE",
        "com.google.android.apps.nbu.files"     to "ACTION_MANAGE_STORAGE",
        "com.sec.android.app.myfiles"           to "ACTION_MANAGE_STORAGE",
        "com.xiaomi.fileexplorer"               to "ACTION_MANAGE_STORAGE",

        // WiFi / Network
        "com.android.settings.wifi"             to "ACTION_WIFI_SETTINGS",
        "com.google.android.apps.chromecast.app" to "ACTION_WIFI_SETTINGS",

        // Bluetooth / Audio devices
        "com.android.settings.bluetooth"        to "ACTION_BLUETOOTH_SETTINGS",
        "com.samsung.android.app.galaxybuds"    to "ACTION_BLUETOOTH_SETTINGS",
        "com.sony.songpal.mdrconnect"           to "ACTION_BLUETOOTH_SETTINGS",
        "com.bose.bosemusic"                    to "ACTION_BLUETOOTH_SETTINGS",
        "com.jabra.sport"                       to "ACTION_BLUETOOTH_SETTINGS",

        // Sound / Music — opening music = user cares about sound
        "com.spotify.music"                     to "ACTION_SOUND_SETTINGS",
        "com.google.android.music"              to "ACTION_SOUND_SETTINGS",
        "com.apple.android.music"               to "ACTION_SOUND_SETTINGS",
        "com.amazon.mp3"                        to "ACTION_SOUND_SETTINGS",
        "com.soundcloud.android"                to "ACTION_SOUND_SETTINGS",
        "com.pandora.android"                   to "ACTION_SOUND_SETTINGS",
        "com.miui.player"                       to "ACTION_SOUND_SETTINGS",
        "com.sec.android.app.music"             to "ACTION_SOUND_SETTINGS",

        // Do Not Disturb — night / focus apps
        "com.urbandroid.sleep"                  to "ACTION_DND",
        "com.shazam.android"                    to "ACTION_SOUND_SETTINGS",
        "com.calm.android"                      to "ACTION_DND",
        "com.headspace.android"                 to "ACTION_DND",
        "com.endel.endel"                       to "ACTION_DND",

        // Display / Screen
        "com.google.android.youtube"            to "ACTION_BRIGHTNESS",
        "com.netflix.mediaclient"               to "ACTION_BRIGHTNESS",
        "com.amazon.avod.thirdpartyclient"      to "ACTION_BRIGHTNESS",
        "com.disney.disneyplus"                 to "ACTION_BRIGHTNESS",

        // Location
        "com.google.android.apps.maps"          to "ACTION_LOCATION_SETTINGS",
        "com.waze"                              to "ACTION_LOCATION_SETTINGS",
        "com.ubercab"                           to "ACTION_LOCATION_SETTINGS",
        "com.grabtaxi.passenger"                to "ACTION_LOCATION_SETTINGS",
        "com.here.app.maps"                     to "ACTION_LOCATION_SETTINGS",

        // Airplane mode — travel apps
        "com.booking.android"                   to "ACTION_AIRPLANE_MODE",
        "com.airbnb.android"                    to "ACTION_AIRPLANE_MODE",
        "net.skyscanner.android.main"           to "ACTION_AIRPLANE_MODE",
        "com.tripadvisor.tripadvisor"           to "ACTION_AIRPLANE_MODE",

        // Data usage — data-heavy apps when away from WiFi
        "com.instagram.android"                 to "ACTION_DATA_USAGE",
        "com.twitter.android"                   to "ACTION_DATA_USAGE",
        "com.tiktok.musically"                  to "ACTION_DATA_USAGE",

        // Privacy / Security
        "org.thoughtcrime.securesms"            to "ACTION_PRIVACY_SETTINGS",
        "org.telegram.messenger"                to "ACTION_PRIVACY_SETTINGS",
        "com.whatsapp"                          to "ACTION_PRIVACY_SETTINGS",
        "com.bitwarden.mobile"                  to "ACTION_SECURITY_SETTINGS",
        "com.onepassword.android"               to "ACTION_SECURITY_SETTINGS",
        "com.google.android.apps.authenticator2" to "ACTION_SECURITY_SETTINGS",
        "com.authy.authy"                       to "ACTION_SECURITY_SETTINGS",

        // Notifications
        "com.microsoft.teams"                   to "ACTION_NOTIFICATION_SETTINGS",
        "com.slack"                             to "ACTION_NOTIFICATION_SETTINGS",
        "com.discord"                           to "ACTION_NOTIFICATION_SETTINGS",

        // Date / Time / Productivity
        "com.google.android.calendar"           to "ACTION_DATE_TIME",
        "com.microsoft.office.outlook"          to "ACTION_DATE_TIME",
        "com.todoist.android.Todoist"           to "ACTION_DATE_TIME",
        "com.ticktick.task"                     to "ACTION_DATE_TIME",
        "com.any.do"                            to "ACTION_DATE_TIME",

        // Language
        "com.duolingo"                          to "ACTION_LANGUAGE",
        "com.google.android.apps.translate"     to "ACTION_LANGUAGE",
        "com.microsoft.translator"              to "ACTION_LANGUAGE",

        // Accessibility
        "com.google.android.marvin.talkback"    to "ACTION_TALKBACK",
        "com.samsung.android.accessibility"     to "ACTION_ACCESSIBILITY",

        // Dark mode / Display
        "com.kapp.youtube.dark"                 to "ACTION_DARK_MODE",
        "com.topjohnwu.magisk"                  to "ACTION_DEVELOPER_OPTIONS",
        "eu.chainfire.supersu"                  to "ACTION_DEVELOPER_OPTIONS",

        // Screen timeout / Battery optimization
        "com.gmd.shortcut.lockscreen"           to "ACTION_SCREEN_TIMEOUT",
        "com.teslacoilsw.launcher"              to "ACTION_SCREEN_TIMEOUT",

        // Hotspot / VPN
        "com.nordvpn.android"                   to "ACTION_VPN",
        "com.expressvpn.vpn"                    to "ACTION_VPN",
        "com.protonvpn.android"                 to "ACTION_VPN",
        "com.privateinternetaccess.android"     to "ACTION_VPN",
        "org.torproject.torbrowser"             to "ACTION_VPN",
        "com.keexybox.keexy"                    to "ACTION_HOTSPOT",

        // NFC / Payments
        "com.google.android.apps.walletnfcrel"  to "ACTION_NFC",
        "com.samsung.android.spay"              to "ACTION_NFC",
        "com.android.nfc"                       to "ACTION_NFC",

        // Cast / Screen mirror
        "com.google.android.apps.chromecast.app" to "ACTION_CAST",
        "com.sec.android.smartmirroring"        to "ACTION_CAST",
        "com.milink.service"                    to "ACTION_CAST",

        // Game mode
        "com.google.android.play.games"         to "ACTION_GAME_MODE",
        "com.pubg.imobile"                      to "ACTION_GAME_MODE",
        "com.activision.callofduty.shooter"     to "ACTION_GAME_MODE",
        "com.garena.game.freefire"              to "ACTION_GAME_MODE",
        "com.supercell.clashofclans"            to "ACTION_GAME_MODE",
        "com.riotgames.league.wildrift"         to "ACTION_GAME_MODE",

        // Digital wellbeing
        "com.google.android.apps.wellbeing"     to "ACTION_DIGITAL_WELLBEING",
        "com.samsung.android.forest"            to "ACTION_DIGITAL_WELLBEING",

        // Focus / DND
        "com.forestapp.Forest"                  to "ACTION_FOCUS_MODE",
        "com.onetouchapp.one"                   to "ACTION_FOCUS_MODE",
        "io.finch.android"                      to "ACTION_FOCUS_MODE",

        // Sleep
        "com.urbandroid.sleep"                  to "ACTION_SLEEP_SETTINGS",
        "com.sleepeasysolutions.sleepaid"        to "ACTION_SLEEP_SETTINGS",
        "com.relaxio.calmsleep"                 to "ACTION_SLEEP_SETTINGS",

        // Stress / Mindfulness
        "com.calm.android"                      to "ACTION_STRESS_MONITOR",
        "com.headspace.android"                 to "ACTION_STRESS_MONITOR",
        "com.endel.endel"                       to "ACTION_STRESS_MONITOR",

        // Health & Fitness
        "com.google.android.apps.fitness"       to "ACTION_HEALTH_SETTINGS",
        "com.samsung.shealth"                   to "ACTION_HEALTH_SETTINGS",
        "com.xiaomi.hm.health"                  to "ACTION_STEPS_COUNTER",
        "com.fitbit.FitbitMobile"               to "ACTION_STEPS_COUNTER",
        "com.garmin.android.apps.connectmobile" to "ACTION_STEPS_COUNTER",
        "com.strava"                            to "ACTION_STEPS_COUNTER",
        "com.nike.plusgps"                      to "ACTION_STEPS_COUNTER",

        // Emergency SOS
        "com.android.emergency"                 to "ACTION_EMERGENCY_SOS",
        "com.samsung.android.emergency"         to "ACTION_EMERGENCY_SOS",

        // Smart Home
        "com.google.android.apps.chromecast.app" to "ACTION_SMART_HOME",
        "com.amazon.echo"                       to "ACTION_SMART_HOME",
        "com.philips.lighting.hue"              to "ACTION_SMART_HOME",
        "com.tp_link.tplinknbu"                 to "ACTION_SMART_HOME",
        "com.xiaomi.smarthome"                  to "ACTION_SMART_HOME",
        "io.homeassistant.companion.android"    to "ACTION_SMART_HOME",
        "com.tuya.smart"                        to "ACTION_SMART_HOME",

        // Routines / Shortcuts
        "net.dinglisch.android.taskerm"         to "ACTION_ROUTINES",
        "com.joaomgcd.join"                     to "ACTION_ROUTINES",
        "com.samsung.android.app.routines"      to "ACTION_ROUTINES",

        // Camera / Gallery
        "com.google.android.GoogleCamera"       to "ACTION_CAMERA_SETTINGS",
        "com.sec.android.app.camera"            to "ACTION_CAMERA_SETTINGS",
        "com.miui.camera"                       to "ACTION_CAMERA_SETTINGS",
        "com.oneplus.camera"                    to "ACTION_CAMERA_SETTINGS",
        "com.google.android.apps.photos"        to "ACTION_GALLERY",
        "com.miui.gallery"                      to "ACTION_GALLERY",
        "com.sec.android.gallery3d"             to "ACTION_GALLERY",
        "com.instagram.android"                 to "ACTION_PHOTO_QUALITY",

        // Keyboard / Input
        "com.google.android.inputmethod.latin"  to "ACTION_KEYBOARD",
        "com.samsung.android.honeyboard"        to "ACTION_KEYBOARD",
        "com.swiftkey.swiftkeyapp"              to "ACTION_KEYBOARD",
        "com.touchtype.swiftkey"                to "ACTION_KEYBOARD",
        "com.grammarly.android.keyboard"        to "ACTION_SPELL_CHECK",
        "com.google.android.googlequicksearchbox" to "ACTION_VOICE_INPUT",

        // Nearby Share / File transfer
        "com.google.android.gms"                to "ACTION_NEARBY_SHARE",
        "com.xiaomi.mishare"                    to "ACTION_NEARBY_SHARE",
        "com.samsung.android.aware.service"     to "ACTION_NEARBY_SHARE",
        "com.shareit.lite"                      to "ACTION_NEARBY_SHARE",
    )

    // ── Data classes ──────────────────────────────────────────────────────────
    data class UsageEvent(
        val packageName: String,
        val appLabel:    String,
        val intent:      String,      // mapped Axiom intent
        val hour:        Int,
        val dayOfWeek:   Int,         // Calendar.MONDAY .. Calendar.SUNDAY
        val timestampMs: Long
    )

    data class AnalysisResult(
        val events:          List<UsageEvent>,
        val intentHourMap:   Map<String, Map<Int, Int>>,   // intent → hour → count
        val totalEvents:     Int,
        val mappedEvents:    Int,
        val daysAnalysed:    Int
    )

    // ── Permission check ──────────────────────────────────────────────────────
    fun hasPermission(context: Context): Boolean {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 24 * 3600 * 1000L,
            now
        )
        return stats != null && stats.isNotEmpty()
    }

    fun openPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ── Main analysis ─────────────────────────────────────────────────────────
    // Reads up to `days` days of usage history, maps to intents, returns result.
    fun analyse(context: Context, days: Int = 30): AnalysisResult {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm  = context.packageManager
        val now = System.currentTimeMillis()
        val from = now - days * 24 * 3600 * 1000L

        val usageEvents = mutableListOf<UsageEvent>()
        val intentHourMap = mutableMapOf<String, MutableMap<Int, Int>>()

        // Query raw events (foreground moves = app opens)
        val events = usm.queryEvents(from, now)
        val event  = UsageEvents.Event()
        var totalRaw = 0

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
            totalRaw++

            val pkg    = event.packageName ?: continue
            val intent = PACKAGE_TO_INTENT[pkg] ?: continue   // unmapped → skip

            val cal = Calendar.getInstance().apply { timeInMillis = event.timeStamp }
            val hour      = cal.get(Calendar.HOUR_OF_DAY)
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)

            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) { pkg }

            usageEvents.add(UsageEvent(pkg, label, intent, hour, dayOfWeek, event.timeStamp))

            // Accumulate intent-hour counts
            intentHourMap.getOrPut(intent) { mutableMapOf() }
                .merge(hour, 1, Int::plus)
        }

        return AnalysisResult(
            events        = usageEvents,
            intentHourMap = intentHourMap,
            totalEvents   = totalRaw,
            mappedEvents  = usageEvents.size,
            daysAnalysed  = days
        )
    }

    // ── feedIntoAxiom — REMOVED ──────────────────────────────────────────────
    // Previously bootstrapped the adapter with 30 days of historical data by
    // treating every observed app open as a synthetic "accepted" training event.
    //
    // REMOVED because:
    //   • PACKAGE_USAGE_STATS is now justified as "showing insights about your
    //     habits" (AxiomProfileActivity, InsightEngine) — clear and honest.
    //   • Silently seeding the adapter from history is harder to explain and
    //     invites scrutiny for the same permission.
    //   • OnboardingActivity sets the cold-start expectation correctly.
    //   • Real accept/reject feedback is higher quality signal anyway.
    //
    // analyse() is still called — its output feeds InsightEngine only.
    // runUsageScan() now updates the temporal table (axiom_preds.csv) only.

    // ── Persist event log for Event Viewer ────────────────────────────────────
    fun saveEventLog(context: Context, result: AnalysisResult) {
        val arr = JSONArray()
        result.events.takeLast(500).forEach { ev ->   // keep latest 500
            arr.put(JSONObject().apply {
                put("pkg",    ev.packageName)
                put("app",    ev.appLabel)
                put("intent", ev.intent)
                put("hour",   ev.hour)
                put("dow",    ev.dayOfWeek)
                put("ts",     ev.timestampMs / 1000)
            })
        }
        File(context.filesDir, "axiom_usage.json").writeText(arr.toString(2))
    }

    // ── Summary stats for UI ──────────────────────────────────────────────────
    fun summaryStats(result: AnalysisResult): String {
        val topIntents = result.intentHourMap
            .mapValues { it.value.values.sum() }
            .entries.sortedByDescending { it.value }
            .take(5)
            .joinToString(", ") { "${it.key.removePrefix("ACTION_")}(${it.value})" }

        return buildString {
            append("Days analysed: ${result.daysAnalysed}\n")
            append("App opens seen: ${result.totalEvents}\n")
            append("Mapped to intents: ${result.mappedEvents}\n")
            append("Top patterns: $topIntents")
        }
    }
}