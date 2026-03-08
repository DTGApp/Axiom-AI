package com.axiom.axiomnew

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.*
import android.graphics.Typeface
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ════════════════════════════════════════════════════════════════════════════
//  AxiomProfileActivity — "What Axiom knows about you"
//
//  This is the screen that makes someone show their phone to a friend.
//  It surfaces everything Axiom has silently learned — in plain English,
//  with real numbers, no JSON, no developer jargon.
//
//  Sections:
//    1. Learning status   — how long Axiom has been watching, how trained it is
//    2. Habit snapshot    — phone pickups, peak hours, morning ritual
//    3. Your top habits   — the 3–5 strongest behavioural patterns identified
//    4. Dormant apps      — things installed that haven't been opened
//    5. Prediction record — how often Axiom's suggestions have been right
//
//  Data sources:
//    • UsageStatsManager     — app opens, screen-on time, pickup counts
//    • axiom_adapter.bin     — via AxiomEngine.getAdapterStats()
//    • axiom_preds.csv       — temporal habits (hour-of-day patterns)
//    • axiom_events.jsonl    — interaction history (accepts / rejects)
//    • SharedPreferences     — install date, interaction count
// ════════════════════════════════════════════════════════════════════════════
class AxiomProfileActivity : AppCompatActivity() {

    // ── Service binding ───────────────────────────────────────────────────────
    private var axiomService: AxiomService? = null
    private var serviceBound  = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            axiomService = (binder as AxiomService.LocalBinder).getService()
            serviceBound = true
            loadAllSections()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            axiomService = null; serviceBound = false
        }
    }

    // ── Colours ───────────────────────────────────────────────────────────────
    private val colBg       = 0xFF0A0C12.toInt()
    private val colCard     = 0xFF141720.toInt()
    private val colAccent   = 0xFF00C8FF.toInt()
    private val colGreen    = 0xFF34D399.toInt()
    private val colYellow   = 0xFFFBBF24.toInt()
    private val colMuted    = 0xFF6B7280.toInt()
    private val colText     = 0xFFE5E7EB.toInt()
    private val colSubtext  = 0xFF9CA3AF.toInt()
    private val colDivider  = 0xFF1F2937.toInt()

    // ── Root scroll + container ───────────────────────────────────────────────
    private lateinit var container: LinearLayout

    // ── Prefs ─────────────────────────────────────────────────────────────────
    companion object {
        const val PREFS_PROFILE  = "axiom_profile"
        const val KEY_INSTALL_TS = "install_ts"
    }

    // ═════════════════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Record install date on first open
        val prefs = getSharedPreferences(PREFS_PROFILE, MODE_PRIVATE)
        if (!prefs.contains(KEY_INSTALL_TS)) {
            prefs.edit().putLong(KEY_INSTALL_TS, System.currentTimeMillis()).apply()
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(colBg)
            isVerticalScrollBarEnabled = false
        }

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        scroll.addView(container, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setContentView(scroll)

        supportActionBar?.apply {
            title = "What Axiom Knows"
            setDisplayHomeAsUpEnabled(true)
        }

        showLoadingSkeleton()

        bindService(
            Intent(this, AxiomService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ═════════════════════════════════════════════════════════════════════════
    //  Skeleton while loading
    // ═════════════════════════════════════════════════════════════════════════
    private fun showLoadingSkeleton() {
        container.removeAllViews()
        container.addView(makeHeaderCard())
        container.addView(makeLoadingCard("Reading your patterns…"))
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Main loader — runs on background thread, posts to UI
    // ═════════════════════════════════════════════════════════════════════════
    private fun loadAllSections() {
        Thread {
            val profile = buildProfile()
            runOnUiThread { renderProfile(profile) }
        }.start()
    }

    private fun renderProfile(p: AxiomProfile) {
        container.removeAllViews()

        // 1. Header — learning status
        container.addView(makeLearningStatusCard(p))

        // 2. Snapshot numbers
        if (p.hasUsagePermission) {
            container.addView(makeSnapshotCard(p))
        } else {
            container.addView(makePermissionPromptCard())
        }

        // 3. Strongest habits
        if (p.habits.isNotEmpty()) {
            container.addView(makeSectionHeader("Your habits"))
            p.habits.forEach { container.addView(makeHabitRow(it)) }
        }

        // 4. Dormant apps
        if (p.dormantApps.isNotEmpty()) {
            container.addView(makeSectionHeader("Apps you've stopped using"))
            p.dormantApps.take(4).forEach { container.addView(makeDormantRow(it)) }
        }

        // 5. Prediction record
        container.addView(makePredictionCard(p))

        // 6. Footer note
        container.addView(makePrivacyNote())
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Data model
    // ═════════════════════════════════════════════════════════════════════════
    data class HabitEntry(
        val emoji:       String,
        val headline:    String,     // "You open YouTube every morning"
        val detail:      String,     // "Usually within 12 min of waking, at 7 AM"
        val strength:    Float       // 0..1 for bar
    )

    data class DormantApp(
        val friendlyName: String,
        val daysSince:    Int,
        val lastOpenTime: String      // "last opened Tuesday"
    )

    data class AxiomProfile(
        // Learning state
        val daysRunning:        Int,
        val totalInteractions:  Int,
        val adapterConfidence:  Float,   // 0..1
        val learningPhase:      String,  // "Just started", "Getting there", "Knows you well"

        // Usage snapshot (requires permission)
        val hasUsagePermission: Boolean,
        val pickupsToday:       Int,
        val peakPickupHour:     String,  // "2 PM – 4 PM"
        val screenTimeToday:    String,  // "3h 12m"
        val topMorningApp:      String,  // "YouTube"
        val morningAppMins:     Int,     // minutes after waking
        val eveningDndHour:     String,  // "10 PM" or ""

        // Habits
        val habits: List<HabitEntry>,

        // Dormant
        val dormantApps: List<DormantApp>,

        // Prediction quality
        val acceptRate:         Int,     // % of suggestions accepted
        val totalSuggestions:   Int
    )

    // ═════════════════════════════════════════════════════════════════════════
    //  Build profile from all data sources
    // ═════════════════════════════════════════════════════════════════════════
    private fun buildProfile(): AxiomProfile {
        val prefs          = getSharedPreferences(PREFS_PROFILE, MODE_PRIVATE)
        val installTs      = prefs.getLong(KEY_INSTALL_TS, System.currentTimeMillis())
        val daysRunning    = ((System.currentTimeMillis() - installTs) / 86_400_000L).toInt().coerceAtLeast(1)
        val hasPerm        = UsageAnalyser.hasPermission(this)

        // Adapter stats from JNI
        val adapterStats   = runCatching { AxiomEngine.getAdapterStats() }.getOrElse { "" }
        val totalInteractions = parseAdapterStat(adapterStats, "interactions")
        val acceptCount       = parseAdapterStat(adapterStats, "accepts")
        val adapterConf       = (totalInteractions.coerceAtMost(100) / 100f).coerceIn(0f, 1f)

        val learningPhase = when {
            totalInteractions < 5   -> "Just started watching"
            totalInteractions < 20  -> "Starting to see patterns"
            totalInteractions < 50  -> "Getting to know you"
            totalInteractions < 100 -> "Knows you fairly well"
            else                    -> "Knows you well"
        }

        // Accept rate
        val acceptRate = if (totalInteractions > 0)
            (acceptCount * 100 / totalInteractions) else 0

        // Usage stats (if permission granted)
        var pickupsToday    = 0
        var peakPickupHour  = ""
        var screenTimeToday = ""
        var topMorningApp   = ""
        var morningAppMins  = 0
        var eveningDndHour  = ""
        val habits          = mutableListOf<HabitEntry>()
        val dormantApps     = mutableListOf<DormantApp>()

        if (hasPerm) {
            val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val dayStart = now - 24 * 3600 * 1000L

            // Today's events for pickup count and peak hour
            val hourPickups = IntArray(24)
            var lastPkg = ""
            var screenOnStart = 0L
            var screenOnTotal = 0L
            val events = usm.queryEvents(dayStart, now)
            val ev = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                when (ev.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        if (ev.packageName != lastPkg) {
                            val cal = Calendar.getInstance().apply { timeInMillis = ev.timeStamp }
                            hourPickups[cal.get(Calendar.HOUR_OF_DAY)]++
                            lastPkg = ev.packageName
                        }
                    }
                    UsageEvents.Event.SCREEN_INTERACTIVE -> screenOnStart = ev.timeStamp
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        if (screenOnStart > 0) {
                            screenOnTotal += ev.timeStamp - screenOnStart
                            screenOnStart = 0
                        }
                    }
                }
            }

            pickupsToday    = hourPickups.sum().coerceAtLeast(0)
            val peakHour    = hourPickups.indices.maxByOrNull { hourPickups[it] } ?: -1
            peakPickupHour  = if (peakHour >= 0) formatHourRange(peakHour) else ""

            val screenMins  = (screenOnTotal / 60_000L).toInt()
            screenTimeToday = formatDuration(screenMins)

            // 7-day stats for habits
            val weekStart   = now - 7 * 24 * 3600 * 1000L
            val weekStats   = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, weekStart, now)
                ?.filter { it.packageName != packageName && it.totalTimeInForeground > 5 * 60_000L }
                ?.sortedByDescending { it.totalTimeInForeground }
                ?: emptyList()

            // Hourly opens for habit detection
            val hourlyOpens = mutableMapOf<String, IntArray>() // pkg → 24-int array
            val weekEvents  = usm.queryEvents(weekStart, now)
            val wev         = android.app.usage.UsageEvents.Event()
            val firstOpens  = mutableMapOf<String, Long>()    // pkg → earliest open this week

            while (weekEvents.hasNextEvent()) {
                weekEvents.getNextEvent(wev)
                if (wev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    val cal  = Calendar.getInstance().apply { timeInMillis = wev.timeStamp }
                    val hour = cal.get(Calendar.HOUR_OF_DAY)
                    hourlyOpens.getOrPut(wev.packageName) { IntArray(24) }[hour]++
                    if (!firstOpens.containsKey(wev.packageName))
                        firstOpens[wev.packageName] = wev.timeStamp
                }
            }

            // Morning ritual: top app opened between 6–9 AM
            val morningApps = hourlyOpens.entries
                .filter { (pkg, hours) -> pkg != packageName && (hours[6]+hours[7]+hours[8]+hours[9]) > 3 }
                .maxByOrNull { (_, hours) -> hours[6]+hours[7]+hours[8]+hours[9] }

            if (morningApps != null) {
                topMorningApp  = getAppLabel(morningApps.key)
                val morningHour = (6..9).maxByOrNull { morningApps.value[it] } ?: 7
                morningAppMins = morningHour * 60 - 7 * 60 // rough mins after "wake" at 7AM
            }

            // Evening DND: detect if user consistently goes quiet after 9 PM
            val eveningTotal = hourPickups.slice(21..23).sum() + (if (hourPickups.size > 0) hourPickups[0] else 0)
            if (pickupsToday > 20 && eveningTotal < pickupsToday / 10) {
                eveningDndHour = "10 PM"
            }

            // Build habit entries from top weekly apps
            weekStats.take(5).forEach { stat ->
                val pkg        = stat.packageName
                val opens      = hourlyOpens[pkg]
                val peakHr     = opens?.indices?.maxByOrNull { opens[it] } ?: -1
                val totalMins  = (stat.totalTimeInForeground / 60_000L).toInt()
                val dailyMins  = totalMins / 7
                val daysActive = ((firstOpens[pkg]?.let { System.currentTimeMillis() - it } ?: 0L) / 86_400_000L).toInt().coerceIn(1, 7)

                if (peakHr >= 0 && totalMins > 20) {
                    habits.add(HabitEntry(
                        emoji     = appEmoji(pkg),
                        headline  = "${getAppLabel(pkg)} — ${formatDuration(dailyMins)}/day",
                        detail    = "Usually around ${formatHour(peakHr)}, ${daysActive} of 7 days",
                        strength  = (totalMins / 300f).coerceIn(0.05f, 1f)
                    ))
                }
            }

            // Dormant apps: installed but not opened in 7 days
            val recentPkgs = weekStats.map { it.packageName }.toSet()
            val installedPkgs = packageManager.getInstalledApplications(0)
                .filter { it.packageName != packageName }
                .map { it.packageName }

            installedPkgs
                .filter { pkg -> !recentPkgs.contains(pkg) }
                .filter { pkg ->
                    // Only meaningful apps (has launcher intent)
                    packageManager.getLaunchIntentForPackage(pkg) != null
                }
                .take(4)
                .forEach { pkg ->
                    val lastUsed = weekStats.find { it.packageName == pkg }
                    dormantApps.add(DormantApp(
                        friendlyName = getAppLabel(pkg),
                        daysSince    = 7,
                        lastOpenTime = "not opened this week"
                    ))
                }
        }

        return AxiomProfile(
            daysRunning        = daysRunning,
            totalInteractions  = totalInteractions,
            adapterConfidence  = adapterConf,
            learningPhase      = learningPhase,
            hasUsagePermission = hasPerm,
            pickupsToday       = pickupsToday,
            peakPickupHour     = peakPickupHour,
            screenTimeToday    = screenTimeToday,
            topMorningApp      = topMorningApp,
            morningAppMins     = morningAppMins,
            eveningDndHour     = eveningDndHour,
            habits             = habits,
            dormantApps        = dormantApps,
            acceptRate         = acceptRate,
            totalSuggestions   = totalInteractions
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Card builders
    // ═════════════════════════════════════════════════════════════════════════

    private fun makeHeaderCard(): LinearLayout = card().apply {
        addView(label("What Axiom knows about you", 20, colText, Typeface.BOLD))
        addView(vspace(4))
        addView(label("Everything on this page comes from your phone. Nothing leaves it.", 13, colMuted))
    }

    private fun makeLearningStatusCard(p: AxiomProfile): LinearLayout = card().apply {
        val daysStr = if (p.daysRunning == 1) "1 day" else "${p.daysRunning} days"

        addView(row(
            label("🧠  Axiom has been learning for", 13, colMuted),
            label(daysStr, 13, colAccent, Typeface.BOLD)
        ))
        addView(vspace(12))

        // Progress bar
        addView(progressBar(p.adapterConfidence, colAccent))
        addView(vspace(6))
        addView(label(p.learningPhase, 13, colSubtext))
        addView(vspace(12))

        addView(divider())
        addView(vspace(12))

        addView(label("${p.totalInteractions} interactions recorded", 14, colText))
        if (p.totalInteractions < 5) {
            addView(vspace(4))
            addView(label("Use Axiom 5 more times and patterns will start to form.", 13, colMuted))
        } else if (p.totalInteractions < 50) {
            addView(vspace(4))
            val remaining = 50 - p.totalInteractions
            addView(label("$remaining more interactions until Axiom can bypass the LLM entirely.", 13, colMuted))
        }
    }

    private fun makeSnapshotCard(p: AxiomProfile): LinearLayout = card().apply {
        addView(label("Today at a glance", 15, colText, Typeface.BOLD))
        addView(vspace(12))

        if (p.pickupsToday > 0) {
            addView(statRow("📱", "Phone pickups",
                "${p.pickupsToday}${if (p.peakPickupHour.isNotEmpty()) "  ·  most at ${p.peakPickupHour}" else ""}"))
        }

        if (p.screenTimeToday.isNotEmpty() && p.screenTimeToday != "0m") {
            addView(statRow("🖥", "Screen time", p.screenTimeToday))
        }

        if (p.topMorningApp.isNotEmpty()) {
            addView(statRow("☀️", "Morning ritual",
                "First to ${p.topMorningApp} — every morning"))
        }

        if (p.eveningDndHour.isNotEmpty()) {
            addView(statRow("🌙", "Evening quiet", "Phone goes quiet around ${p.eveningDndHour}"))
        }

        if (p.pickupsToday == 0 && p.screenTimeToday.isEmpty()) {
            addView(label("Come back later today — Axiom is still collecting.", 13, colMuted))
        }
    }

    private fun makePermissionPromptCard(): LinearLayout = card().apply {
        addView(label("📊  Unlock personal insights", 15, colText, Typeface.BOLD))
        addView(vspace(8))
        addView(label(
            "Grant Usage Access and Axiom can show you real numbers: how many times you open Instagram, " +
                    "what your morning habits actually are, and which apps you've quietly stopped using.",
            13, colSubtext
        ))
        addView(vspace(12))

        val btn = Button(this@AxiomProfileActivity).apply {
            text = "Grant Usage Access"
            setTextColor(colBg)
            backgroundTintList = android.content.res.ColorStateList.valueOf(colAccent)
            textSize = 14f
            setOnClickListener {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
        addView(btn)
    }

    private fun makeSectionHeader(title: String): TextView = TextView(this).apply {
        text = title.uppercase()
        textSize = 11f
        setTextColor(colMuted)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.12f
        setPadding(dp(4), dp(20), dp(4), dp(8))
    }

    private fun makeHabitRow(h: HabitEntry): LinearLayout = card().apply {
        val header = LinearLayout(this@AxiomProfileActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(label(h.emoji, 22, colText).apply { minWidth = dp(36) })
        val text = LinearLayout(this@AxiomProfileActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        text.addView(label(h.headline, 14, colText, Typeface.BOLD))
        text.addView(vspace(2))
        text.addView(label(h.detail, 12, colSubtext))
        header.addView(text)
        addView(header)
        addView(vspace(10))
        addView(progressBar(h.strength, colGreen))
    }

    private fun makeDormantRow(d: DormantApp): LinearLayout = card().apply {
        addView(row(
            label("💤  ${d.friendlyName}", 14, colSubtext),
            label(d.lastOpenTime, 12, colMuted)
        ))
    }

    private fun makePredictionCard(p: AxiomProfile): LinearLayout = card().apply {
        addView(label("Prediction accuracy", 15, colText, Typeface.BOLD))
        addView(vspace(8))

        if (p.totalSuggestions < 3) {
            addView(label("Not enough data yet. Accept or reject a few suggestions to see how well Axiom is predicting.", 13, colSubtext))
        } else {
            val colour = when {
                p.acceptRate >= 75 -> colGreen
                p.acceptRate >= 50 -> colYellow
                else               -> 0xFFEF4444.toInt()
            }
            addView(label("${p.acceptRate}% of suggestions accepted", 28, colour, Typeface.BOLD))
            addView(vspace(4))
            addView(label("across ${p.totalSuggestions} total suggestions", 13, colMuted))
            addView(vspace(10))
            addView(progressBar(p.acceptRate / 100f, colour))
            addView(vspace(8))
            val advice = when {
                p.acceptRate >= 80 -> "Axiom knows you well. Keep using it."
                p.acceptRate >= 60 -> "Getting better. When it's wrong, use the label picker — it learns faster from specific corrections."
                p.acceptRate >= 40 -> "Still learning. Reject wrong suggestions and label them correctly."
                else               -> "Use the label picker when rejecting. That's how Axiom knows what the right answer actually was."
            }
            addView(label(advice, 13, colSubtext))
        }
    }

    private fun makePrivacyNote(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(16), dp(8), dp(8))

        addView(label(
            "🔒  Everything above is computed on your phone. " +
                    "No data is ever sent anywhere. Uninstalling Axiom deletes all of it.",
            12, colMuted
        ))
    }

    private fun makeLoadingCard(message: String): LinearLayout = card().apply {
        addView(label(message, 14, colMuted))
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  View helpers
    // ═════════════════════════════════════════════════════════════════════════

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(colCard)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(10)) }
        layoutParams = lp
        // Rounded corners via background
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(colCard)
            cornerRadius = dp(12).toFloat()
        }
    }

    private fun label(text: String, size: Int, color: Int, style: Int = Typeface.NORMAL) =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            typeface = Typeface.create(Typeface.DEFAULT, style)
        }

    private fun vspace(dp: Int) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(dp)
        )
    }

    private fun divider() = android.view.View(this).apply {
        setBackgroundColor(colDivider)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        )
    }

    private fun row(left: android.view.View, right: android.view.View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            left.layoutParams  = lp
            right.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(left); addView(right)
        }

    private fun statRow(emoji: String, label: String, value: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))

            addView(label(emoji, 18, colText).apply {
                minWidth = dp(36)
            })
            addView(LinearLayout(this@AxiomProfileActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, 0, 0)
                addView(label(label, 12, colMuted))
                addView(label(value, 14, colText, Typeface.BOLD))
            })
        }

    private fun progressBar(fraction: Float, color: Int): android.widget.ProgressBar =
        android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = (fraction * 100).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(color)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(colDivider)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
            )
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // ═════════════════════════════════════════════════════════════════════════
    //  Data helpers
    // ═════════════════════════════════════════════════════════════════════════

    private fun parseAdapterStat(stats: String, key: String): Int {
        // Adapter stats are returned as "key=value\nkey=value\n…"
        return runCatching {
            stats.lines()
                .firstOrNull { it.startsWith("$key=") }
                ?.substringAfter("=")?.trim()?.toInt() ?: 0
        }.getOrElse { 0 }
    }

    private fun formatHour(hour: Int): String = when {
        hour == 0  -> "midnight"
        hour < 12  -> "$hour AM"
        hour == 12 -> "noon"
        else       -> "${hour - 12} PM"
    }

    private fun formatHourRange(peak: Int): String {
        val from = formatHour(peak)
        val to   = formatHour((peak + 2).coerceAtMost(23))
        return "$from – $to"
    }

    private fun formatDuration(mins: Int): String {
        if (mins <= 0) return "—"
        val h = mins / 60; val m = mins % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    private fun getAppLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    }.getOrElse { pkg.split(".").last().replaceFirstChar { it.uppercase() } }

    private fun appEmoji(pkg: String): String = when {
        "youtube"   in pkg -> "▶️"
        "spotify"   in pkg -> "🎵"
        "instagram" in pkg -> "📸"
        "twitter"   in pkg || "x.android" in pkg -> "𝕏"
        "whatsapp"  in pkg -> "💬"
        "maps"      in pkg -> "🗺️"
        "camera"    in pkg -> "📷"
        "chrome"    in pkg || "browser" in pkg -> "🌐"
        "gmail"     in pkg || "mail" in pkg -> "📧"
        "netflix"   in pkg -> "🎬"
        "tiktok"    in pkg -> "🎶"
        "reddit"    in pkg -> "🔴"
        "fitness"   in pkg || "health" in pkg || "strava" in pkg -> "🏃"
        "sleep"     in pkg || "clock" in pkg -> "⏰"
        else -> "📱"
    }
}