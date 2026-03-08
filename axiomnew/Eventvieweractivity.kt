package com.axiom.axiomnew

import android.graphics.Typeface
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ════════════════════════════════════════════════════════════════════════════
//  EventViewerActivity
//
//  Three tabs: Usage | Events | Learning
//
//  Uses a plain LinearLayout tab bar + ViewFlipper instead of tab host.
//  tab host is deprecated and crashes on MIUI (Xiaomi) because its internal
//  tab-indicator drawables return resource ID 0x00000000 on that ROM.
//
//  All data loading runs on background threads to prevent ANR.
//  JNI call (AxiomEngine.getAdapterStats) is also off the main thread.
// ════════════════════════════════════════════════════════════════════════════
class EventViewerActivity : AppCompatActivity() {

    // ── Views ────────────────────────────────────────────────────────────────
    private lateinit var tvUsage:    TextView
    private lateinit var tvEvents:   TextView
    private lateinit var tvLearning: TextView
    private lateinit var btnRefresh: Button
    private lateinit var btnAnalyse: Button
    private lateinit var flipper:    ViewFlipper
    private lateinit var tabButtons: List<TextView>

    // Colour constants
    private val colBg      = 0xFF0F1117.toInt()
    private val colCard    = 0xFF1A1D27.toInt()
    private val colTabOn   = 0xFF00C8FF.toInt()
    private val colTabOff  = 0xFF374151.toInt()
    private val colTabBgOn = 0xFF0D1E2C.toInt()
    private val colText    = 0xFFD1FAE5.toInt()
    private val colMuted   = 0xFF6B7280.toInt()
    private val colWhite   = 0xFFFFFFFF.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        supportActionBar?.title = "Axiom Event Viewer"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        loadAll()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Layout ───────────────────────────────────────────────────────────────
    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colBg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // ── Action button row ────────────────────────────────────────────────
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(8))
        }
        btnAnalyse = Button(this).apply {
            text = "Analyse Usage"
            setTextColor(colWhite)
            setBackgroundColor(0xFF1D4ED8.toInt())
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
            setOnClickListener { runUsageAnalysis() }
        }
        btnRefresh = Button(this).apply {
            text = "Refresh"
            setTextColor(colWhite)
            setBackgroundColor(colTabOff)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { loadAll() }
        }
        actionRow.addView(btnAnalyse)
        actionRow.addView(btnRefresh)
        root.addView(actionRow)

        // ── Custom tab bar (3 TextViews) ─────────────────────────────────────
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(colCard)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val tabLabels = listOf("Usage", "Events", "Learning")
        tabButtons = tabLabels.mapIndexed { index, label ->
            TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(14), 0, dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { switchTab(index) }
            }.also { tabRow.addView(it) }
        }
        root.addView(tabRow)

        // Thin accent line under tab bar
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(0xFF2D3748.toInt())
        })

        // ── ViewFlipper — one child per tab ──────────────────────────────────
        flipper = ViewFlipper(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        tvUsage    = makeContentView()
        tvEvents   = makeContentView()
        tvLearning = makeContentView()

        flipper.addView(makeScrollPane(tvUsage))
        flipper.addView(makeScrollPane(tvEvents))
        flipper.addView(makeScrollPane(tvLearning))

        root.addView(flipper)

        // Activate first tab
        switchTab(0)
        return root
    }

    private fun switchTab(index: Int) {
        flipper.displayedChild = index
        tabButtons.forEachIndexed { i, btn ->
            if (i == index) {
                btn.setTextColor(colTabOn)
                btn.setBackgroundColor(colTabBgOn)
            } else {
                btn.setTextColor(colTabOff)
                btn.setBackgroundColor(colCard)
            }
        }
    }

    private fun makeContentView() = TextView(this).apply {
        setTextColor(colText)
        setBackgroundColor(colBg)
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setPadding(dp(16), dp(12), dp(16), dp(12))
        text = "Loading..."
    }

    private fun makeScrollPane(tv: TextView) = ScrollView(this).apply {
        setBackgroundColor(colBg)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT)
        addView(tv)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    // ── Load all tabs off the main thread ────────────────────────────────────
    private fun loadAll() {
        tvUsage.text    = "Loading..."
        tvEvents.text   = "Loading..."
        tvLearning.text = "Loading..."
        Thread { val t = buildUsageText();    runOnUiThread { tvUsage.text    = t } }.start()
        Thread { val t = buildEventsText();   runOnUiThread { tvEvents.text   = t } }.start()
        Thread { val t = buildLearningText(); runOnUiThread { tvLearning.text = t } }.start()
    }

    // ── Tab 1: Usage ─────────────────────────────────────────────────────────
    private fun buildUsageText(): String {
        if (!UsageAnalyser.hasPermission(this)) {
            return "USAGE ACCESS REQUIRED\n\n" +
                    "Axiom needs permission to read your app usage history.\n\n" +
                    "Tap 'Analyse Usage' to open the settings screen.\n\n" +
                    "Settings > Apps > Special App Access > Usage Access\n" +
                    "Toggle ON for Axiom.\n\n" +
                    "This data never leaves your device."
        }

        val usageFile = File(filesDir, "axiom_usage.json")
        if (!usageFile.exists()) {
            return "No usage data yet.\nTap 'Analyse Usage' to scan."
        }

        return try {
            val arr        = JSONArray(usageFile.readText())
            val sb         = StringBuilder()
            val hourCounts = mutableMapOf<String, MutableMap<Int, Int>>()
            val fmtDate    = SimpleDateFormat("MMM dd", Locale.getDefault())
            val fmtTime    = SimpleDateFormat("HH:mm",  Locale.getDefault())

            sb.append("APP USAGE  (").append(arr.length()).append(" events)\n\n")

            var lastDate = ""
            for (i in arr.length() - 1 downTo 0) {
                val ev     = arr.getJSONObject(i)
                val tsMs   = ev.optLong("ts") * 1000L
                val date   = fmtDate.format(Date(tsMs))
                val time   = fmtTime.format(Date(tsMs))
                val app    = ev.optString("app", ev.optString("pkg"))
                val intent = ev.optString("intent")
                val hour   = ev.optInt("hour")

                if (date != lastDate) {
                    sb.append("\n-- ").append(date).append(" --\n")
                    lastDate = date
                }
                sb.append("  ").append(time)
                    .append("  ").append(intent.removePrefix("ACTION_").padEnd(22))
                    .append("  ").append(app).append("\n")
                hourCounts.getOrPut(intent) { mutableMapOf() }.merge(hour, 1, Int::plus)
            }

            sb.append("\n\nHOURLY PATTERNS\n")
            for ((intent, hours) in hourCounts.entries.sortedBy { it.key }) {
                sb.append("\n").append(intent.removePrefix("ACTION_")).append("\n")
                for ((hour, count) in hours.entries.sortedBy { it.key }) {
                    val bar = "|".repeat(minOf(count, 20))
                    sb.append("  ").append(hour.toString().padStart(2))
                        .append(":00  ").append(bar).append(" ").append(count).append("\n")
                }
            }
            sb.toString()
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ── Tab 2: Events ─────────────────────────────────────────────────────────
    private fun buildEventsText(): String {
        val eventsFile = File(filesDir, "axiom_events.jsonl")
        if (!eventsFile.exists()) {
            return "No events yet.\nMake a request in the main screen first."
        }

        return try {
            val lines = eventsFile.readLines().filter { it.isNotBlank() }.reversed()
            val sb    = StringBuilder()
            val sdf   = SimpleDateFormat("MMM dd HH:mm:ss", Locale.getDefault())

            sb.append("INFERENCE & FEEDBACK LOG\n")
            sb.append(lines.size).append(" events  (newest first)\n\n")

            var accepted = 0; var rejected = 0; var pending = 0

            for (line in lines) {
                try {
                    val j      = JSONObject(line)
                    val ts     = j.optLong("ts") * 1000L
                    val intent = j.optString("intent", "?")
                    val conf   = j.optDouble("confidence", 0.0)
                    val acc    = j.optInt("accepted", -1)
                    val time   = sdf.format(Date(ts))

                    val icon = when (acc) {
                        1    -> { accepted++; "OK" }
                        0    -> { rejected++; "NO" }
                        else -> { pending++;  " ?" }
                    }

                    sb.append(icon).append(" ").append(time).append("\n")
                    if (intent != "<<feedback>>" && intent != "<<notif_feedback>>") {
                        sb.append("   ").append(intent.removePrefix("ACTION_")).append("\n")
                        if (conf > 0) {
                            sb.append("   conf: ").append("%.0f".format(conf * 100)).append("%\n")
                        }
                    } else {
                        sb.append("   [feedback: ").append(intent).append("]\n")
                    }
                    sb.append("\n")
                } catch (_: Exception) { }
            }

            val total = accepted + rejected
            sb.append("--\n")
            sb.append("OK:").append(accepted)
                .append("  NO:").append(rejected)
                .append("  ?:").append(pending).append("\n")
            if (total > 0) {
                sb.append("Accuracy: ").append("%.0f".format(accepted.toFloat() / total * 100))
                    .append("%\n")
            }
            sb.toString()
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ── Tab 3: Learning ───────────────────────────────────────────────────────
    // AxiomEngine.getAdapterStats() is a blocking JNI call — safe here because
    // this runs on a background thread (called from loadAll()).
    private fun buildLearningText(): String {
        val sb = StringBuilder()
        sb.append("ADAPTER STATE\n\n")

        try {
            val stats    = JSONObject(AxiomEngine.getAdapterStats())
            val updates  = stats.optInt("total_updates")
            val acc      = stats.optInt("total_accepted")
            val rej      = stats.optInt("total_rejected")
            val accuracy = if (updates > 0) acc.toFloat() / updates * 100 else 0f

            sb.append("Updates  : ").append(updates).append("\n")
            sb.append("Accepted : ").append(acc).append("\n")
            sb.append("Rejected : ").append(rej).append("\n")
            sb.append("Accuracy : ").append("%.0f".format(accuracy)).append("%\n\n")

            val level = when {
                updates >= 500 -> "Master   - LLM rarely used"
                updates >= 200 -> "Advanced - frequent bypass"
                updates >= 50  -> "Learning - bypass active"
                updates >= 10  -> "Early    - warming up"
                else           -> "Seed     - needs feedback"
            }
            sb.append("Level    : ").append(level).append("\n")
        } catch (e: Exception) {
            sb.append("Stats unavailable - open main screen first\n")
        }

        sb.append("\nPREDICTION TABLE\n\n")
        val predFile = File(filesDir, "axiom_preds.csv")
        if (predFile.exists()) {
            try {
                val rows = predFile.readLines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .mapNotNull { row ->
                        val p = row.split(",")
                        if (p.size < 4) null
                        else Triple(
                            p[0].trim(),
                            p[1].trim().toIntOrNull() ?: -1,
                            Pair(p[2].trim().toIntOrNull() ?: 0,
                                p[3].trim().toIntOrNull() ?: 0))
                    }
                    .filter { it.third.second > 0 }
                    .sortedByDescending { it.third.first.toFloat() / it.third.second }

                sb.append("Intent                  Hr  Hit%\n")
                sb.append("--------------------------------\n")
                for ((intent, hour, ht) in rows.take(30)) {
                    val pct = ht.first.toFloat() / ht.second * 100
                    sb.append(intent.removePrefix("ACTION_").take(20).padEnd(22))
                        .append(hour.toString().padStart(3)).append("h  ")
                        .append("%.0f".format(pct).padStart(3)).append("%\n")
                }
                if (rows.size > 30) sb.append("...and ").append(rows.size - 30).append(" more\n")
            } catch (e: Exception) {
                sb.append("Error: ").append(e.message).append("\n")
            }
        } else {
            sb.append("No prediction data yet.\n")
        }

        sb.append("\nDREAM CYCLE\n\n")
        val dreamFile = File(filesDir, "dream_summary.json")
        if (dreamFile.exists()) {
            try {
                val j   = JSONObject(dreamFile.readText())
                val ts  = j.optLong("dream_ts") * 1000L
                val sdf = SimpleDateFormat("MMM dd yyyy HH:mm", Locale.getDefault())
                sb.append("Time    : ").append(sdf.format(Date(ts))).append("\n")
                sb.append("Kept    : ").append(j.optInt("events_after")).append("\n")
                sb.append("Removed : ").append(j.optInt("removed")).append("\n")
                val dl = j.optString("dream_learning")
                if (dl.isNotEmpty() && dl != "\"engine_not_ready\"") {
                    try {
                        val dlj = JSONObject(dl)
                        sb.append("Trained : ").append(dlj.optInt("events")).append(" events\n")
                    } catch (_: Exception) { }
                }
            } catch (e: Exception) {
                sb.append("Error: ").append(e.message).append("\n")
            }
        } else {
            sb.append("No dream cycle yet.\n")
            sb.append("Runs at 8 PM while charging.\n")
        }

        sb.append("\nUSAGE ANALYSIS\n\n")
        val usageFile = File(filesDir, "axiom_usage.json")
        if (usageFile.exists()) {
            try {
                val arr   = JSONArray(usageFile.readText())
                val counts = mutableMapOf<String, Int>()
                for (i in 0 until arr.length()) {
                    counts.merge(arr.getJSONObject(i).optString("intent"), 1, Int::plus)
                }
                sb.append("Total mapped: ").append(arr.length()).append("\n\n")
                for ((intent, count) in counts.entries.sortedByDescending { it.value }.take(10)) {
                    sb.append("  ").append(intent.removePrefix("ACTION_").padEnd(25))
                        .append(" ").append(count).append("\n")
                }
            } catch (e: Exception) {
                sb.append("Error: ").append(e.message).append("\n")
            }
        } else {
            sb.append("No usage data. Tap 'Analyse Usage'.\n")
        }

        return sb.toString()
    }

    // ── Run usage analysis ────────────────────────────────────────────────────
    private fun runUsageAnalysis() {
        if (!UsageAnalyser.hasPermission(this)) {
            UsageAnalyser.openPermissionSettings(this)
            Toast.makeText(this, "Grant Usage Access, then tap Analyse again",
                Toast.LENGTH_LONG).show()
            return
        }

        btnAnalyse.isEnabled = false
        btnAnalyse.text = "Analysing..."

        Thread {
            try {
                val result = UsageAnalyser.analyse(this, days = 30)
                UsageAnalyser.saveEventLog(this, result)

                // feedIntoAxiom removed — adapter trained from real accept/reject only
                val summary = UsageAnalyser.summaryStats(result)
                runOnUiThread {
                    btnAnalyse.isEnabled = true
                    btnAnalyse.text = "Analyse Usage"
                    Toast.makeText(this, "Done!\n$summary", Toast.LENGTH_LONG).show()
                    loadAll()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btnAnalyse.isEnabled = true
                    btnAnalyse.text = "Analyse Usage"
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}