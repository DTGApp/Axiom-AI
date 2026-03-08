
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

import android.graphics.Color
import android.os.Bundle
import android.os.StatFs
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// ════════════════════════════════════════════════════════════════════════════
//  HealthCheckActivity
//  Reads every on-device file and SharedPreferences key directly.
//  No service binding required — fully self-contained.
//
//  Colour rules for status dots:
//    GREEN  (#00C87A) = present and recently updated / correct
//    AMBER  (#FFAA00) = present but stale, or partially initialised
//    RED    (#FF4444) = missing, broken, or never initialised
//    GREY   (#3F5975) = informational / not applicable yet
// ════════════════════════════════════════════════════════════════════════════
class HealthCheckActivity : AppCompatActivity() {

    companion object {
        private val FMT_TS   = SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault())
        private val FMT_FULL = SimpleDateFormat("dd MMM yyyy · HH:mm:ss", Locale.getDefault())

        private const val GREEN = "#00C87A"
        private const val AMBER = "#FFAA00"
        private const val RED   = "#FF4444"
        private const val GREY  = "#3F5975"

        // Staleness thresholds
        private val STALE_EVENTS_MS  = TimeUnit.DAYS.toMillis(3)   // events log: stale after 3 days
        private val STALE_ADAPTER_MS = TimeUnit.DAYS.toMillis(7)   // adapter: stale after 7 days
        private val STALE_USAGE_MS   = TimeUnit.DAYS.toMillis(2)   // usage scan: stale after 2 days
        private val STALE_DREAM_MS   = TimeUnit.DAYS.toMillis(3)   // dream: stale after 3 days
        private val STALE_INSIGHT_MS = TimeUnit.DAYS.toMillis(14)  // insight: stale after 14 days
    }

    // ── Data class for a row's display state ────────────────────────────────
    private data class RowState(
        val dot:    String,   // colour hex
        val label:  String,
        val detail: String,
        val value:  String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_health_check)
        supportActionBar?.hide()
        runChecks()
        findViewById<TextView>(R.id.btnRefreshHealth).setOnClickListener { runChecks() }
    }

    // ── Entry point ──────────────────────────────────────────────────────────
    private fun runChecks() {
        val now = System.currentTimeMillis()

        // Update "last checked" timestamp
        findViewById<TextView>(R.id.tvHealthTimestamp)
            .text = "Last checked: ${FMT_FULL.format(Date(now))}"

        // ── Core Engine ────────────────────────────────────────────────────
        bind(R.id.rowModel,     checkModel())
        bind(R.id.rowEngine,    checkEngine())
        bind(R.id.rowNativeLib, checkNativeLib())

        // ── Learning Pipeline ──────────────────────────────────────────────
        bind(R.id.rowEvents,    checkEvents(now))
        bind(R.id.rowAdapter,   checkAdapter(now))
        bind(R.id.rowPreds,     checkPreds(now))
        bind(R.id.rowUsageScan, checkUsageScan(now))

        // ── Dream Cycle ────────────────────────────────────────────────────
        bind(R.id.rowDreamState,   checkDreamState())
        bind(R.id.rowLastDream,    checkLastDream(now))
        bind(R.id.rowDreamSummary, checkDreamSummary(now))
        bind(R.id.rowDreamData,    checkDreamData(now))

        // ── Intelligence ───────────────────────────────────────────────────
        bind(R.id.rowInsight,    checkInsight(now))
        bind(R.id.rowMode,       checkMode())
        bind(R.id.rowLifeContext, checkLifeContext())

        // ── App State ──────────────────────────────────────────────────────
        bind(R.id.rowOnboarding,  checkOnboarding())
        bind(R.id.rowMilestones,  checkMilestones())
        bind(R.id.rowInstallAge,  checkInstallAge(now))
        bind(R.id.rowStorage,     checkStorage())
    }

    // ── Bind a RowState into an included item_health_row layout ─────────────
    private fun bind(rowId: Int, state: RowState) {
        val row = findViewById<View>(rowId) ?: return
        row.findViewById<View>(R.id.viewRowDot)
            .setBackgroundColor(Color.parseColor(state.dot))
        row.findViewById<TextView>(R.id.tvRowLabel).text  = state.label
        row.findViewById<TextView>(R.id.tvRowDetail).text = state.detail
        row.findViewById<TextView>(R.id.tvRowValue).text  = state.value
    }

    // ════════════════════════════════════════════════════════════════════════
    //  CORE ENGINE
    // ════════════════════════════════════════════════════════════════════════

    private fun checkModel(): RowState {
        val f = File(filesDir, "axiom_seed_q4.gguf")
        return if (f.exists()) {
            val mb   = f.length() / (1024L * 1024L)
            val aged = FMT_TS.format(Date(f.lastModified()))
            RowState(GREEN, "LLM Seed Model", "axiom_seed_q4.gguf · copied $aged",
                "${mb} MB")
        } else {
            RowState(RED, "LLM Seed Model",
                "axiom_seed_q4.gguf not found — download required", "MISSING")
        }
    }

    private fun checkEngine(): RowState {
        // Proxy check: if model file is present AND AxiomService is running
        // (foreground notification visible), engine is likely loaded.
        // We read the live engineReady flag via the static companion on AxiomEngine.
        val modelPresent = File(filesDir, "axiom_seed_q4.gguf").exists()
        val engineReady  = runCatching { AxiomEngine.ready }.getOrDefault(false)
        return when {
            engineReady  -> RowState(GREEN, "AxiomEngine JNI",
                "Initialised and running", "READY")
            modelPresent -> RowState(AMBER, "AxiomEngine JNI",
                "Model present but engine not yet loaded", "LOADING")
            else         -> RowState(RED,   "AxiomEngine JNI",
                "Model missing — engine cannot start", "NOT READY")
        }
    }

    private fun checkNativeLib(): RowState {
        return try {
            System.loadLibrary("axiom_engine")
            RowState(GREEN, "Native Library", "libaxiom_engine.so loaded successfully", "OK")
        } catch (e: UnsatisfiedLinkError) {
            RowState(RED, "Native Library",
                "libaxiom_engine.so failed: ${e.message?.take(60)}", "FAILED")
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  LEARNING PIPELINE
    // ════════════════════════════════════════════════════════════════════════

    private fun checkEvents(now: Long): RowState {
        val f = File(filesDir, "axiom_events.jsonl")
        if (!f.exists()) return RowState(AMBER, "Events Log",
            "axiom_events.jsonl — no interactions recorded yet", "EMPTY")
        val lines  = f.readLines().count { it.isNotBlank() }
        val ageMs  = now - f.lastModified()
        val aged   = FMT_TS.format(Date(f.lastModified()))
        val dot    = if (ageMs > STALE_EVENTS_MS) AMBER else GREEN
        val detail = "Last entry: $aged  ·  ${f.length() / 1024L} KB"
        return RowState(dot, "Events Log", detail, "$lines lines")
    }

    private fun checkAdapter(now: Long): RowState {
        val f = File(filesDir, "axiom_adapter.bin")
        if (!f.exists()) return RowState(AMBER, "MLP Adapter",
            "axiom_adapter.bin — not yet written (need 5+ interactions)", "ABSENT")
        val kb   = f.length() / 1024L
        val ageMs = now - f.lastModified()
        val aged  = FMT_TS.format(Date(f.lastModified()))
        val dot   = if (ageMs > STALE_ADAPTER_MS) AMBER else GREEN
        return RowState(dot, "MLP Adapter", "Last updated: $aged", "${kb} KB")
    }

    private fun checkPreds(now: Long): RowState {
        val f = File(filesDir, "axiom_preds.csv")
        if (!f.exists()) return RowState(AMBER, "Predictions Table",
            "axiom_preds.csv — populated after first usage scan", "ABSENT")
        val rows  = f.readLines().count { it.isNotBlank() }
        val aged  = FMT_TS.format(Date(f.lastModified()))
        val ageMs = now - f.lastModified()
        val dot   = if (ageMs > STALE_USAGE_MS * 2) AMBER else GREEN
        return RowState(dot, "Predictions Table", "Last updated: $aged", "$rows rows")
    }

    private fun checkUsageScan(now: Long): RowState {
        // File check
        val f = File(filesDir, "axiom_usage.json")
        // Prefs check
        val lastScanSec = getSharedPreferences(ChargingReceiver.PREFS_NAME, MODE_PRIVATE)
            .getLong(ChargingReceiver.KEY_LAST_USAGE_TS, 0L)
        val lastScanMs = lastScanSec * 1000L

        val fileInfo = if (f.exists()) {
            val kb = f.length() / 1024L
            val aged = FMT_TS.format(Date(f.lastModified()))
            "axiom_usage.json · $kb KB · $aged"
        } else "axiom_usage.json absent"

        return when {
            lastScanMs == 0L ->
                RowState(AMBER, "Usage Scan", "Never run yet — triggers daily", "NEVER")
            now - lastScanMs > STALE_USAGE_MS ->
                RowState(AMBER, "Usage Scan", "$fileInfo\nPrefs: ${FMT_TS.format(Date(lastScanMs))}",
                    ago(now - lastScanMs))
            else ->
                RowState(GREEN, "Usage Scan", "$fileInfo\nPrefs: ${FMT_TS.format(Date(lastScanMs))}",
                    ago(now - lastScanMs))
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  DREAM CYCLE
    // ════════════════════════════════════════════════════════════════════════

    private fun checkDreamState(): RowState {
        val prefs = getSharedPreferences(DreamService.PREFS_DREAM_UI, MODE_PRIVATE)
        val state = prefs.getString(DreamService.KEY_DREAM_STATE, "idle") ?: "idle"
        val tsMs  = prefs.getLong(DreamService.KEY_DREAM_TS, 0L)
        val tsStr = if (tsMs > 0L) FMT_TS.format(Date(tsMs)) else "—"
        val (dot, valueStr) = when (state) {
            "dreaming" -> AMBER to "RUNNING"
            "complete" -> GREEN to "DONE"
            "missed"   -> AMBER to "MISSED"
            else       -> GREY  to "IDLE"
        }
        return RowState(dot, "Dream State",
            "axiom_dream_ui · dream_state · last at $tsStr", valueStr)
    }

    private fun checkLastDream(now: Long): RowState {
        val lastSec = getSharedPreferences(ChargingReceiver.PREFS_NAME, MODE_PRIVATE)
            .getLong(ChargingReceiver.KEY_LAST_DREAM_TS, 0L)
        val lastMs = lastSec * 1000L
        if (lastMs == 0L) return RowState(AMBER, "Last Dream Ran",
            "axiom_prefs · last_dream_ts — never triggered yet", "NEVER")
        val ageMs = now - lastMs
        val dot   = if (ageMs > STALE_DREAM_MS) AMBER else GREEN
        return RowState(dot, "Last Dream Ran",
            "axiom_prefs · ${FMT_TS.format(Date(lastMs))}", ago(ageMs))
    }

    private fun checkDreamSummary(now: Long): RowState {
        val f = File(filesDir, "dream_summary.json")
        if (!f.exists()) return RowState(GREY, "Dream Summary File",
            "dream_summary.json — written after first dream cycle", "ABSENT")
        return try {
            val j       = JSONObject(f.readText())
            val events  = j.optInt("events_before", -1)
            val trained = j.optInt("surprisal_events", -1)
            val removed = j.optInt("removed", -1)
            val tsMs    = j.optLong("dream_ts", 0L) * 1000L
            val ageMs   = now - f.lastModified()
            val dot     = if (ageMs > STALE_DREAM_MS) AMBER else GREEN
            val detail  = buildString {
                append("dream_summary.json · ${FMT_TS.format(Date(f.lastModified()))}")
                if (events >= 0) append("\n$events events · $trained surprisal · $removed removed")
            }
            RowState(dot, "Dream Summary File", detail, ago(ageMs))
        } catch (e: Exception) {
            RowState(AMBER, "Dream Summary File",
                "Parse error: ${e.message?.take(50)}", "BAD JSON")
        }
    }

    private fun checkDreamData(now: Long): RowState {
        val f = File(filesDir, "dream_data.json")
        if (!f.exists()) return RowState(GREY, "Dream Data File",
            "dream_data.json — surprisal events fed to learning", "ABSENT")
        val lines = runCatching {
            org.json.JSONArray(f.readText()).length()
        }.getOrDefault(-1)
        val aged  = FMT_TS.format(Date(f.lastModified()))
        val ageMs = now - f.lastModified()
        val dot   = if (ageMs > STALE_DREAM_MS) AMBER else GREEN
        val count = if (lines >= 0) "$lines surprisal events" else "${f.length() / 1024L} KB"
        return RowState(dot, "Dream Data File", "dream_data.json · $aged", count)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  INTELLIGENCE
    // ════════════════════════════════════════════════════════════════════════

    private fun checkInsight(now: Long): RowState {
        val prefs    = getSharedPreferences(InsightEngine.PREFS_NAME, MODE_PRIVATE)
        val lastMs   = prefs.getLong(InsightEngine.KEY_LAST_INSIGHT, 0L)
        val lastText = prefs.getString(InsightEngine.KEY_LAST_TEXT, null)
        if (lastMs == 0L) return RowState(GREY, "Weekly Insight",
            "axiom_insights · no insight generated yet (needs 7 days of data)", "NEVER")
        val ageMs  = now - lastMs
        val dot    = if (ageMs > STALE_INSIGHT_MS) AMBER else GREEN
        val preview = lastText?.take(40)?.let { if (it.length == 40) "$it…" else it } ?: "—"
        return RowState(dot, "Weekly Insight",
            "Last: ${FMT_TS.format(Date(lastMs))}\n\"$preview\"", ago(ageMs))
    }

    private fun checkMode(): RowState {
        val prefs      = getSharedPreferences(PhoneModeManager.PREFS_NAME, MODE_PRIVATE)
        val mode       = prefs.getString(PhoneModeManager.KEY_CURRENT_MODE, null)
        val lastSwitchMs = prefs.getLong(PhoneModeManager.KEY_LAST_SWITCH, 0L)
        val switchStr  = if (lastSwitchMs > 0L) FMT_TS.format(Date(lastSwitchMs)) else "never"
        if (mode == null) return RowState(GREY, "Auto Mode",
            "axiom_mode — no mode detected yet", "IDLE")
        val log    = prefs.getString(PhoneModeManager.KEY_MODE_LOG, "") ?: ""
        val swaps  = log.lines().count { it.isNotBlank() }
        return RowState(GREEN, "Auto Mode",
            "Current: $mode · last switch: $switchStr", "$swaps switches")
    }

    private fun checkLifeContext(): RowState {
        val prefs  = getSharedPreferences(LifeContextAssistant.PREFS_NAME, MODE_PRIVATE)
        val raw    = prefs.getString(LifeContextAssistant.KEY_NOTIFIED_EVENTS, null)
        val count  = runCatching {
            if (raw.isNullOrEmpty()) 0 else org.json.JSONArray(raw).length()
        }.getOrDefault(0)
        // If calendar permission is granted it's running; we can only check the prefs record
        val (dot, val1) = if (raw != null) GREEN to "$count notified" else GREY to "0 notified"
        return RowState(dot, "Life Context",
            "axiom_life_context · calendar event reminders", val1)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  APP STATE
    // ════════════════════════════════════════════════════════════════════════

    private fun checkOnboarding(): RowState {
        val done = getSharedPreferences(OnboardingActivity.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(OnboardingActivity.KEY_ONBOARDING_DONE, false)
        return if (done)
            RowState(GREEN, "Onboarding", "axiom_onboarding · completed", "DONE")
        else
            RowState(AMBER, "Onboarding", "axiom_onboarding · not yet completed", "PENDING")
    }

    private fun checkMilestones(): RowState {
        val prefs = getSharedPreferences("axiom_milestones", MODE_PRIVATE)
        val reached = listOf(5, 20, 50, 100).filter {
            prefs.getBoolean("milestone_$it", false)
        }
        val highest = reached.maxOrNull()
        val dot     = if (highest != null && highest >= 20) GREEN
        else if (highest != null) AMBER
        else GREY
        val detail  = if (reached.isEmpty()) "axiom_milestones · no milestones yet"
        else "axiom_milestones · reached: ${reached.joinToString(", ")}"
        val value   = highest?.let { "${it}+ interactions" } ?: "< 5"
        return RowState(dot, "Milestones", detail, value)
    }

    private fun checkInstallAge(now: Long): RowState {
        val installMs = getSharedPreferences(AxiomProfileActivity.PREFS_PROFILE, MODE_PRIVATE)
            .getLong(AxiomProfileActivity.KEY_INSTALL_TS, 0L)
        if (installMs == 0L) return RowState(GREY, "Install Age",
            "axiom_profile · install_ts not yet recorded", "UNKNOWN")
        val days = TimeUnit.MILLISECONDS.toDays(now - installMs)
        return RowState(GREEN, "Install Age",
            "axiom_profile · installed ${FMT_TS.format(Date(installMs))}", "$days days")
    }

    private fun checkStorage(): RowState {
        return try {
            val s   = StatFs(filesDir.absolutePath)
            val freeGb = s.availableBlocksLong * s.blockSizeLong / (1024L * 1024L * 1024L)
            val freeMb = s.availableBlocksLong * s.blockSizeLong / (1024L * 1024L)
            val dot = if (freeMb < 200) RED else if (freeMb < 500) AMBER else GREEN
            val str = if (freeGb >= 1) "${freeGb} GB free" else "${freeMb} MB free"
            RowState(dot, "Free Storage", "Internal storage available for logs and model", str)
        } catch (e: Exception) {
            RowState(GREY, "Free Storage", "Could not read StatFs: ${e.message?.take(40)}", "?")
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════════════

    /** Converts a millisecond delta to a human-friendly "X ago" string. */
    private fun ago(ms: Long): String = when {
        ms < 0                           -> "just now"
        ms < TimeUnit.MINUTES.toMillis(2) -> "just now"
        ms < TimeUnit.HOURS.toMillis(1)  -> "${TimeUnit.MILLISECONDS.toMinutes(ms)}m ago"
        ms < TimeUnit.DAYS.toMillis(1)   -> "${TimeUnit.MILLISECONDS.toHours(ms)}h ago"
        else                             -> "${TimeUnit.MILLISECONDS.toDays(ms)}d ago"
    }
}