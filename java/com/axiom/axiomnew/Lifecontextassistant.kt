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
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.TimeUnit

// ════════════════════════════════════════════════════════════════════════════════
//  LifeContextAssistant  —  Feature 4: Calendar-driven proactive life assistant
//
//  Reads calendar events, classifies them (flight, hotel, restaurant, meeting,
//  medical), and posts smart tiered notifications with action buttons.
//
//  Examples:
//    ✈️ "Flight to Amsterdam in 4 hours. Leave for airport by 12:30."
//       [Book Cab]  [Airplane Mode]
//    🍽️ "Dinner at Nobu in 2 hours."  [Open Maps]  [Book Cab]
//    📅 "Sprint Review starts in 30 minutes."  [Calendar]
//
//  Required permission (AndroidManifest.xml):
//    <uses-permission android:name="android.permission.READ_CALENDAR" />
// ════════════════════════════════════════════════════════════════════════════════
class LifeContextAssistant(private val ctx: Context) {

    // ── Enums & data classes ───────────────────────────────────────────────────

    enum class EventType { FLIGHT, HOTEL, RESTAURANT, MEETING, MEDICAL, TRAVEL, GENERAL }
    enum class NotifTier { EARLY, MID, FINAL }

    data class CalendarEvent(
        val id:          Long,
        val title:       String,
        val description: String,
        val location:    String,
        val startMs:     Long,
        val endMs:       Long,
        val allDay:      Boolean
    )

    data class LifeEvent(
        val raw:        CalendarEvent,
        val type:       EventType,
        val destination:String?,   // "Amsterdam", "Marriott Berlin"
        val flightCode: String?    // "BA492" if found in title/description
    )

    data class NotifAction(
        val label:            String,
        val settingsAction:   String,
        val packageCandidates:List<String> = emptyList()
    )

    data class LifeNotification(
        val event:   LifeEvent,
        val tier:    NotifTier,
        val title:   String,
        val body:    String,
        val actions: List<NotifAction>
    )

    // ── Single companion object ────────────────────────────────────────────────

    companion object {
        const val PREFS_NAME          = "axiom_life_context"
        const val KEY_NOTIFIED_EVENTS = "notified_events"
        const val NOTIF_CHANNEL_LIFE  = "axiom_life"
        const val NOTIF_BASE_ID       = 1000
        const val SCAN_GAP_MS         = 15L * 60 * 1000
        const val LOOKAHEAD_MS        = 24L * 3600 * 1000

        // Shared LLM busy flag — prevents concurrent JNI calls which crash the native engine.
        @Volatile var llmBusy: Boolean = false

        private val NOTIFY_BEFORE = mapOf(
            EventType.FLIGHT     to listOf(
                TimeUnit.HOURS.toMillis(24),
                TimeUnit.HOURS.toMillis(4),
                TimeUnit.HOURS.toMillis(1)
            ),
            EventType.HOTEL      to listOf(TimeUnit.HOURS.toMillis(12), TimeUnit.HOURS.toMillis(2)),
            EventType.MEETING    to listOf(TimeUnit.MINUTES.toMillis(30), TimeUnit.MINUTES.toMillis(5)),
            EventType.RESTAURANT to listOf(TimeUnit.HOURS.toMillis(2), TimeUnit.MINUTES.toMillis(30)),
            EventType.MEDICAL    to listOf(TimeUnit.HOURS.toMillis(24), TimeUnit.HOURS.toMillis(2)),
            EventType.TRAVEL     to listOf(TimeUnit.HOURS.toMillis(12)),
            EventType.GENERAL    to listOf(TimeUnit.MINUTES.toMillis(15))
        )

        /** Call from AxiomService.createChannels() */
        fun createChannel(ctx: Context) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(NOTIF_CHANNEL_LIFE) != null) return
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        NOTIF_CHANNEL_LIFE, "Life Reminders",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Flight, hotel, meeting and calendar reminders"
                        enableVibration(true)
                        setShowBadge(true)
                    }
                )
            }
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Called by AxiomService every 15 min. Returns posted notifications for logging. */
    fun check(): List<LifeNotification> {
        if (!hasCalendarPermission()) return emptyList()
        val now    = System.currentTimeMillis()
        val events = readCalendarEvents(now, now + LOOKAHEAD_MS)
        val posted = mutableListOf<LifeNotification>()
        events.mapNotNull { classify(it) }.forEach { lifeEvent ->
            val notif = selectNotificationTier(lifeEvent) ?: return@forEach
            if (!wasAlreadyNotified(notif)) {
                postNotification(notif)
                markNotified(notif)
                posted += notif
            }
        }
        return posted
    }

    /** Used by InsightActivity to display upcoming events in the next 24 h. */
    fun getUpcomingEvents(): List<LifeEvent> {
        if (!hasCalendarPermission()) return emptyList()
        val now = System.currentTimeMillis()
        return readCalendarEvents(now, now + LOOKAHEAD_MS).mapNotNull { classify(it) }
    }

    fun hasCalendarPermission(): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    // ── Calendar reading ───────────────────────────────────────────────────────

    // Explicit try/catch(SecurityException) directly around contentResolver.query
    // is the only pattern the compiler's permission analysis accepts — it cannot
    // see through runCatching lambdas or checkSelfPermission guards.
    private fun readCalendarEvents(fromMs: Long, toMs: Long): List<CalendarEvent> {
        val proj = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY
        )
        val sel = "${CalendarContract.Events.DTSTART} >= ? AND " +
                "${CalendarContract.Events.DTSTART} <= ? AND " +
                "${CalendarContract.Events.DELETED} = 0"
        val list = mutableListOf<CalendarEvent>()
        try {
            val cursor: Cursor? = ctx.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, proj, sel,
                arrayOf(fromMs.toString(), toMs.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )
            cursor?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(1) ?: continue
                    if (title.isBlank()) continue
                    list += CalendarEvent(
                        id          = c.getLong(0),
                        title       = title,
                        description = c.getString(2) ?: "",
                        location    = c.getString(3) ?: "",
                        startMs     = c.getLong(4),
                        endMs       = c.getLong(5),
                        allDay      = c.getInt(6) == 1
                    )
                }
            }
        } catch (e: SecurityException) {
            android.util.Log.w("LifeContext", "Calendar permission denied: ${e.message}")
        } catch (e: Exception) {
            android.util.Log.w("LifeContext", "Calendar read error: ${e.message}")
        }
        return list
    }

    // ── Event classification ───────────────────────────────────────────────────

    private fun classify(event: CalendarEvent): LifeEvent? {
        val t = "${event.title} ${event.description} ${event.location}".lowercase()

        if (setOf("flight","boarding","airline","airport","terminal","gate","departure","fly")
                .any { t.contains(it) })
            return LifeEvent(event, EventType.FLIGHT,
                extractDestination(event.title, event.location),
                extractFlightCode("${event.title} ${event.description}"))

        if (setOf("hotel","check-in","hostel","airbnb","accommodation","motel","resort")
                .any { t.contains(it) })
            return LifeEvent(event, EventType.HOTEL,
                extractDestination(event.title, event.location), null)

        if (setOf("restaurant","reservation","dinner","lunch","table for","brunch")
                .any { t.contains(it) })
            return LifeEvent(event, EventType.RESTAURANT,
                extractDestination(event.title, event.location), null)

        if (setOf("doctor","dentist","appointment","hospital","clinic","gp","physio","therapy","checkup")
                .any { t.contains(it) })
            return LifeEvent(event, EventType.MEDICAL, event.location.ifEmpty { null }, null)

        if (setOf("meeting","standup","review","interview","zoom","teams","meet","sync","sprint","1:1","demo")
                .any { t.contains(it) })
            return LifeEvent(event, EventType.MEETING, null, null)

        if (setOf("train","bus","travel","journey","depart","arrive","eurostar","ferry")
                .any { t.contains(it) })
            return LifeEvent(event, EventType.TRAVEL,
                extractDestination(event.title, event.location), null)

        if (AxiomEngine.isEngineReady()) return classifyWithLlm(event)
        return null
    }

    private fun classifyWithLlm(event: CalendarEvent): LifeEvent? {
        val prompt = buildString {
            appendLine("Classify this calendar event into ONE word: FLIGHT HOTEL RESTAURANT MEETING MEDICAL TRAVEL or NONE.")
            appendLine("Event: ${event.title}")
            if (event.location.isNotBlank()) appendLine("Location: ${event.location}")
            append("Type:")
        }
        if (llmBusy) return null
        llmBusy = true
        val rawStr = try {
            AxiomEngine.conversationalAnswer(prompt, 20)
        } finally {
            llmBusy = false
        }
        val raw = rawStr.trim().uppercase()
        if (raw.isEmpty()) return null
        val type = when {
            raw.startsWith("FLIGHT")     -> EventType.FLIGHT
            raw.startsWith("HOTEL")      -> EventType.HOTEL
            raw.startsWith("RESTAURANT") -> EventType.RESTAURANT
            raw.startsWith("MEETING")    -> EventType.MEETING
            raw.startsWith("MEDICAL")    -> EventType.MEDICAL
            raw.startsWith("TRAVEL")     -> EventType.TRAVEL
            else                         -> return null
        }
        return LifeEvent(event, type, extractDestination(event.title, event.location), null)
    }

    // ── Notification tier selection ────────────────────────────────────────────

    private fun selectNotificationTier(event: LifeEvent): LifeNotification? {
        val now       = System.currentTimeMillis()
        val timeUntil = event.raw.startMs - now
        if (timeUntil <= 0) return null

        val windows   = NOTIFY_BEFORE[event.type] ?: NOTIFY_BEFORE[EventType.GENERAL]!!
        val tolerance = SCAN_GAP_MS
        val idx       = windows.indexOfFirst { w -> timeUntil <= w + tolerance && timeUntil >= w - tolerance }
        if (idx < 0) return null

        val tier = when (idx) { 0 -> NotifTier.EARLY; 1 -> NotifTier.MID; else -> NotifTier.FINAL }
        return buildNotification(event, tier, timeUntil)
    }

    // ── Notification content ───────────────────────────────────────────────────

    private fun buildNotification(event: LifeEvent, tier: NotifTier, timeUntilMs: Long): LifeNotification {
        val h = TimeUnit.MILLISECONDS.toHours(timeUntilMs)
        val m = TimeUnit.MILLISECONDS.toMinutes(timeUntilMs) % 60
        val timeStr = when {
            h >= 24 -> "tomorrow"
            h >= 2  -> "in $h hours"
            h >= 1  -> "in 1 hour${if (m > 0) " $m min" else ""}"
            m > 0   -> "in $m minutes"
            else    -> "now"
        }

        val destStr   = event.destination?.let { " to $it" } ?: ""
        val flightStr = event.flightCode?.let { " (${it})" } ?: ""

        val (title, body) = if (AxiomEngine.isEngineReady()) {
            generateLlmCopy(event, timeStr)
        } else {
            buildTemplateCopy(event, tier, timeStr, destStr, flightStr, h)
        }

        return LifeNotification(event, tier, title, body, buildActions(event, h))
    }

    private fun generateLlmCopy(event: LifeEvent, timeStr: String): Pair<String, String> {
        val prompt = buildString {
            appendLine("Write a concise phone notification. Use this exact format:")
            appendLine("TITLE: <max 6 words>")
            appendLine("BODY: <max 20 words>")
            appendLine("Event: ${event.raw.title}")
            appendLine("Time: $timeStr")
            if (event.raw.location.isNotBlank()) appendLine("Location: ${event.raw.location}")
            if (event.flightCode != null)        appendLine("Flight: ${event.flightCode}")
            append("Notification:")
        }
        if (llmBusy) return buildTemplateCopy(event, NotifTier.MID, timeStr, "", "", 0)
        llmBusy = true
        val rawStr = try {
            AxiomEngine.conversationalAnswer(prompt, 80)
        } finally {
            llmBusy = false
        }
        val raw = rawStr.trim()
        if (raw.isEmpty()) return buildTemplateCopy(event, NotifTier.MID, timeStr, "", "", 0)

        val title = Regex("TITLE:\\s*(.+)", RegexOption.IGNORE_CASE).find(raw)
            ?.groupValues?.get(1)?.trim()?.take(60)
        val body  = Regex("BODY:\\s*(.+)",  RegexOption.IGNORE_CASE).find(raw)
            ?.groupValues?.get(1)?.trim()?.take(200)

        return if (!title.isNullOrBlank() && !body.isNullOrBlank()) Pair(title, body)
        else buildTemplateCopy(event, NotifTier.MID, timeStr, "", "", 0)
    }

    private fun buildTemplateCopy(
        event: LifeEvent, tier: NotifTier,
        timeStr: String, destStr: String, flightStr: String, hoursUntil: Long
    ): Pair<String, String> = when (event.type) {

        EventType.FLIGHT -> when (tier) {
            NotifTier.EARLY -> Pair("✈️ Flight${destStr} tomorrow",
                "Your flight${flightStr}${destStr} is tomorrow. Check in online and prepare your documents.")
            NotifTier.MID   -> Pair("✈️ Leave for airport soon",
                "Flight${destStr}${flightStr} departs $timeStr. Allow extra time for check-in.")
            NotifTier.FINAL -> Pair("✈️ Boarding soon${destStr}",
                "Flight${flightStr} departs $timeStr. Head to your gate now.")
        }

        EventType.HOTEL -> when (tier) {
            NotifTier.EARLY -> Pair("🏨 Hotel check-in${destStr} tomorrow",
                "${event.destination ?: "Your hotel"} expects you tomorrow. Check-in is typically 3 PM.")
            else            -> Pair("🏨 Hotel check-in today",
                "Heading to ${event.destination ?: "your hotel"} $timeStr.")
        }

        EventType.RESTAURANT -> when (tier) {
            NotifTier.EARLY -> Pair("🍽️ Dinner reservation $timeStr",
                "${event.destination ?: event.raw.title} is $timeStr." +
                        (event.raw.location.takeIf { it.isNotBlank() }?.let { " At $it." } ?: ""))
            else -> Pair("🍽️ Leaving for dinner soon",
                "${event.destination ?: "Your reservation"} is $timeStr. Order a cab if you haven't.")
        }

        EventType.MEETING -> when (tier) {
            NotifTier.EARLY -> Pair("📅 Meeting $timeStr",
                "${event.raw.title} starts $timeStr." +
                        (event.raw.location.takeIf { it.isNotBlank() }?.let { " Room: $it." } ?: ""))
            else -> Pair("📅 Meeting starting $timeStr",
                "${event.raw.title} — ${event.raw.location.ifBlank { "check calendar for location" }}.")
        }

        EventType.MEDICAL -> when (tier) {
            NotifTier.EARLY -> Pair("🏥 Appointment tomorrow",
                "${event.raw.title} is tomorrow." +
                        (event.raw.location.takeIf { it.isNotBlank() }?.let { " At: $it." } ?: "") +
                        " Remember any referrals or documents.")
            else -> Pair("🏥 Appointment $timeStr",
                "${event.raw.title} — leave enough time for travel.")
        }

        EventType.TRAVEL -> Pair("🚂 Travel $timeStr",
            "${event.raw.title} is $timeStr. Have your tickets ready.")

        else -> Pair("📅 ${event.raw.title}", "${event.raw.title} is $timeStr.")
    }

    // ── Action buttons ─────────────────────────────────────────────────────────

    private fun buildActions(event: LifeEvent, hoursUntil: Long): List<NotifAction> {
        val actions = mutableListOf<NotifAction>()
        when (event.type) {
            EventType.FLIGHT -> {
                actions += NotifAction("🚗 Book Cab", "",
                    listOf("com.ubercab","com.bolt.client","com.google.android.apps.maps"))
                if (hoursUntil <= 3)
                    actions += NotifAction("✈️ Airplane Mode",
                        "android.settings.AIRPLANE_MODE_SETTINGS")
            }
            EventType.HOTEL, EventType.RESTAURANT, EventType.TRAVEL -> {
                actions += NotifAction("🗺️ Open Maps", "",
                    listOf("com.google.android.apps.maps","com.waze"))
                actions += NotifAction("🚗 Book Cab", "",
                    listOf("com.ubercab","com.bolt.client","com.google.android.apps.maps"))
            }
            EventType.MEETING -> {
                if (event.raw.location.isNotBlank()
                    && !event.raw.location.contains("zoom", true)
                    && !event.raw.location.contains("teams", true)) {
                    actions += NotifAction("🗺️ Directions", "",
                        listOf("com.google.android.apps.maps"))
                }
                actions += NotifAction("📅 Calendar", "",
                    listOf("com.google.android.calendar","com.microsoft.office.outlook"))
            }
            EventType.MEDICAL ->
                actions += NotifAction("🗺️ Directions", "",
                    listOf("com.google.android.apps.maps","com.waze"))
            else -> {}
        }
        return actions
    }

    // ── Notification posting ───────────────────────────────────────────────────

    private fun postNotification(notif: LifeNotification) {
        val notifId   = (NOTIF_BASE_ID + (notif.event.raw.id % 99)).toInt()
        val tapPending = PendingIntent.getActivity(
            ctx, notifId, buildCalendarTapIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(ctx, NOTIF_CHANNEL_LIFE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notif.title)
            .setContentText(notif.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notif.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(tapPending)

        notif.actions.take(2).forEachIndexed { idx, action ->
            val pending = PendingIntent.getActivity(
                ctx, notifId * 10 + idx, buildActionIntent(action, notif),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, action.label, pending)
        }

        try {
            NotificationManagerCompat.from(ctx).notify(notifId, builder.build())
            android.util.Log.i("LifeContext", "Posted: ${notif.title} id=$notifId")
        } catch (e: SecurityException) {
            android.util.Log.e("LifeContext", "Notification permission denied: ${e.message}")
        } catch (e: Exception) {
            android.util.Log.e("LifeContext", "Post failed: ${e.message}")
        }
    }

    private fun buildCalendarTapIntent(): Intent {
        val pm = ctx.packageManager
        for (pkg in listOf("com.google.android.calendar",
            "com.microsoft.office.outlook",
            "com.samsung.android.calendar")) {
            runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
                ?.let { return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }
        return Intent("android.settings.SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun buildActionIntent(action: NotifAction, notif: LifeNotification): Intent {
        val pm = ctx.packageManager
        // Maps actions: build geo URI if we have a location
        if ((action.label.contains("Map") || action.label.contains("Direction"))
            && notif.event.raw.location.isNotBlank()) {
            val geo = Uri.parse("geo:0,0?q=${Uri.encode(notif.event.raw.location)}")
            val i   = Intent(Intent.ACTION_VIEW, geo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(pm) != null) return i
        }
        for (pkg in action.packageCandidates) {
            runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
                ?.let { return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }
        if (action.settingsAction.startsWith("android."))
            return Intent(action.settingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return Intent("android.settings.SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // ── Deduplication ──────────────────────────────────────────────────────────

    private fun notifKey(n: LifeNotification): String {
        val day = n.event.raw.startMs / TimeUnit.DAYS.toMillis(1)
        return "${n.event.raw.id}:${n.tier.name}:$day"
    }

    private fun wasAlreadyNotified(notif: LifeNotification): Boolean {
        val sent = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_NOTIFIED_EVENTS, emptySet()) ?: emptySet()
        return notifKey(notif) in sent
    }

    private fun markNotified(notif: LifeNotification) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sent  = (prefs.getStringSet(KEY_NOTIFIED_EVENTS, emptySet()) ?: emptySet()).toMutableSet()
        if (sent.size > 200) sent.clear()
        sent.add(notifKey(notif))
        prefs.edit().putStringSet(KEY_NOTIFIED_EVENTS, sent).apply()
    }

    // ── Entity extraction ──────────────────────────────────────────────────────

    private fun extractDestination(title: String, location: String): String? {
        if (location.isNotBlank())
            return location.lines().firstOrNull()?.trim()?.take(40)
        Regex("\\bto ([A-Z][a-zA-Z ]+)", RegexOption.IGNORE_CASE).find(title)
            ?.groupValues?.get(1)?.trim()?.let { return it.take(30) }
        Regex("([A-Z][a-zA-Z ]+?)\\s+(hotel|restaurant|hostel)", RegexOption.IGNORE_CASE)
            .find(title)?.groupValues?.get(1)?.trim()?.let { return it.take(30) }
        return null
    }

    private fun extractFlightCode(text: String): String? =
        Regex("\\b([A-Z]{2,3}\\s?\\d{3,4})\\b").find(text.uppercase())?.value?.replace(" ", "")
}