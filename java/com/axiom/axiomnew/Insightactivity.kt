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

import android.content.*
import android.os.*
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

// ════════════════════════════════════════════════════════════════════════════════
//  InsightActivity — Full-screen intelligence hub
//
//  Four tabs / sections:
//    1. Battery Doctor   — live on-device AI diagnosis
//    2. Weekly Insight   — last pattern observation from InsightEngine
//    3. Phone Mode       — current auto-mode + switch log
//    4. Usage Summary    — top apps this week (from UsageAnalyser)
//
//  Launched from:
//    • MainActivity "🔍 Insights" button
//    • Insight weekly notification tap
// ════════════════════════════════════════════════════════════════════════════════
class InsightActivity : AppCompatActivity() {

    private var axiomService: AxiomService? = null
    private var serviceBound  = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            axiomService = (binder as AxiomService.LocalBinder).getService()
            serviceBound = true
            onServiceBound()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            axiomService = null; serviceBound = false
        }
    }

    // ── UI ──────────────────────────────────────────────────────────────────
    private lateinit var tvBatteryDiagnosis:   TextView
    private lateinit var tvBatteryLevel:       TextView
    private lateinit var tvTopDrainApp:        TextView
    private lateinit var tvScreenOn:           TextView
    private lateinit var tvPickups:            TextView
    private lateinit var btnRunBatteryDoctor:  Button
    private lateinit var batteryProgress:      ProgressBar

    private lateinit var tvWeeklyInsight:      TextView
    private lateinit var tvInsightDataPoints:  TextView
    private lateinit var btnRefreshInsight:    Button

    private lateinit var tvCurrentMode:        TextView
    private lateinit var tvModeLog:            TextView
    private lateinit var btnResetMode:         Button

    // Guard: the native LLM context is single-threaded. Only one of BatteryDoctor
    // or InsightRefresh may call AxiomEngine.conversationalAnswer() at a time.
    @Volatile private var llmBusy = false

    // ════════════════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_insight)

        supportActionBar?.apply {
            title = "Axiom Intelligence"
            setDisplayHomeAsUpEnabled(true)
        }

        bindViews()

        // If launched from notification, show passed insight directly
        intent.getStringExtra("narrative")?.let { narrative ->
            tvWeeklyInsight.text = narrative
            val points = intent.getStringArrayExtra("data_points") ?: emptyArray()
            tvInsightDataPoints.text = points.joinToString("\n")
        }

        bindService(Intent(this, AxiomService::class.java),
            serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ════════════════════════════════════════════════════════════════════════
    //  View wiring
    // ════════════════════════════════════════════════════════════════════════
    private fun bindViews() {
        tvBatteryDiagnosis  = findViewById(R.id.tvBatteryDiagnosis)
        tvBatteryLevel      = findViewById(R.id.tvBatteryLevel)
        tvTopDrainApp       = findViewById(R.id.tvTopDrainApp)
        tvScreenOn          = findViewById(R.id.tvScreenOn)
        tvPickups           = findViewById(R.id.tvPickups)
        btnRunBatteryDoctor = findViewById(R.id.btnRunBatteryDoctor)
        batteryProgress     = findViewById(R.id.batteryProgress)

        tvWeeklyInsight     = findViewById(R.id.tvWeeklyInsight)
        tvInsightDataPoints = findViewById(R.id.tvInsightDataPoints)
        btnRefreshInsight   = findViewById(R.id.btnRefreshInsight)

        tvCurrentMode       = findViewById(R.id.tvCurrentMode)
        tvModeLog           = findViewById(R.id.tvModeLog)
        btnResetMode        = findViewById(R.id.btnResetMode)

        btnRunBatteryDoctor.setOnClickListener { runBatteryDoctor() }
        btnRefreshInsight.setOnClickListener   { runInsightRefresh() }
        btnResetMode.setOnClickListener        { resetPhoneMode() }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Service bound — populate all sections
    // ════════════════════════════════════════════════════════════════════════
    private fun onServiceBound() {
        updateModeSection()
        loadLastInsight()
        // Battery Doctor is user-initiated only. Do NOT auto-run here.
        // Both BatteryDoctor and InsightRefresh call AxiomEngine.conversationalAnswer()
        // — running them simultaneously causes a native crash (single llama context).
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Section 1: Battery Doctor
    // ════════════════════════════════════════════════════════════════════════
    private fun runBatteryDoctor() {
        val svc = axiomService ?: return
        btnRunBatteryDoctor.isEnabled = false
        btnRunBatteryDoctor.text = "⏳ Diagnosing…"
        tvBatteryDiagnosis.text = "Analysing…"
        batteryProgress.visibility = View.VISIBLE

        Thread {
            if (llmBusy) {
                runOnUiThread {
                    btnRunBatteryDoctor.isEnabled = true
                    btnRunBatteryDoctor.text = "Diagnose Battery"
                    tvBatteryDiagnosis.text = "Axiom is busy — tap again in a moment."
                    batteryProgress.visibility = View.GONE
                }
                return@Thread
            }
            llmBusy = true
            val report = try { svc.runBatteryDiagnosis() } finally { llmBusy = false }
            runOnUiThread {
                batteryProgress.visibility = View.GONE
                btnRunBatteryDoctor.isEnabled = true
                btnRunBatteryDoctor.text = "🔄 Re-diagnose"

                tvBatteryDiagnosis.text = report.summary

                tvBatteryLevel.text = when {
                    report.level < 0  -> "—"
                    report.isCharging -> "🔋 ${report.level}%  ⚡ Charging"
                    report.level < 20 -> "🪫 ${report.level}%  Low"
                    else              -> "🔋 ${report.level}%"
                }

                tvTopDrainApp.text = if (report.topDrain != null) {
                    "📱 ${report.topDrain.friendlyName} — ${report.topDrain.backgroundMinutes} min background\n" +
                            "   Restrict it → save ~${report.topDrain.estimatedSavingsMinutes} min battery"
                } else "📱 No significant background drain detected"

                val screenStr = if (report.screenOnMins > 0) {
                    val h = report.screenOnMins / 60; val m = report.screenOnMins % 60
                    if (h > 0) "${h}h ${m}m" else "${m}m"
                } else "—"
                tvScreenOn.text = "🖥  Screen on today: $screenStr"
                tvPickups.text  = if (report.pickupCount > 0)
                    "📲 Phone pickups today: ${report.pickupCount}" else "📲 —"
            }
        }.start()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Section 2: Weekly Insight
    // ════════════════════════════════════════════════════════════════════════
    private fun loadLastInsight() {
        val last = axiomService?.getLastInsight()

        // Invalidate cached insights that mention system packages — these were
        // generated before the system-app filter was applied. Clear and re-generate.
        val systemNoiseTerms = listOf(
            "system_server", "systemui", "launcher", "com.android",
            "system launcher", "android.process", "digital wellbeing",
            "com.google.android.gms", "screen time"
        )
        val isStale = last != null && systemNoiseTerms.any { term ->
            last.contains(term, ignoreCase = true)
        }

        when {
            isStale -> {
                // Wipe the stale cache — user will see a fresh result next time they tap Refresh.
                // Do NOT auto-trigger runInsightRefresh() here: it would run simultaneously with
                // the auto-started runBatteryDoctor() and both call AxiomEngine.conversationalAnswer().
                // The native llama context is not thread-safe — two concurrent JNI calls = crash.
                axiomService?.clearLastInsight()
                tvWeeklyInsight.text = "Tap Refresh to generate a clean insight (system apps filtered)."
            }
            last != null -> tvWeeklyInsight.text = last
            else -> tvWeeklyInsight.text = "No insight generated yet — tap Refresh to analyse your usage."
        }
    }

    private fun runInsightRefresh() {
        val svc = axiomService ?: return
        btnRefreshInsight.isEnabled = false
        btnRefreshInsight.text = "⏳ Analysing…"
        tvWeeklyInsight.text = "Looking for patterns in your usage…"

        Thread {
            if (llmBusy) {
                runOnUiThread {
                    btnRefreshInsight.isEnabled = true
                    btnRefreshInsight.text = "🔄 Refresh"
                    tvWeeklyInsight.text = "Axiom is busy — tap Refresh again in a moment."
                }
                return@Thread
            }
            llmBusy = true
            val report = try { svc.generateInsightNow() } finally { llmBusy = false }
            runOnUiThread {
                if (report != null) {
                    tvWeeklyInsight.text = report.narrative
                    tvInsightDataPoints.text = report.dataPoints.joinToString("\n") { "• $it" }
                    svc.getLastInsight() // record it
                } else {
                    tvWeeklyInsight.text = "Not enough usage data yet. Come back after a few days of normal use."
                    tvInsightDataPoints.text = ""
                }
            }
        }.start()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Section 3: Phone Mode
    // ════════════════════════════════════════════════════════════════════════
    private fun updateModeSection() {
        val svc  = axiomService ?: return
        val mode = svc.getCurrentPhoneMode()
        val log  = svc.getModeSwitchLog()

        val modeDisplay = when (mode) {
            PhoneModeManager.MODE_SLEEP   -> "🌙 Sleep — Ringer silenced, DND active"
            PhoneModeManager.MODE_COMMUTE -> "🎧 Commute — Media volume boosted"
            PhoneModeManager.MODE_WORK    -> "💼 Work — Vibrate, low-priority notifications filtered"
            PhoneModeManager.MODE_FOCUS   -> "🎯 Focus — All notifications suppressed"
            PhoneModeManager.MODE_GAMING  -> "🎮 Gaming — Max media volume"
            else                          -> "✅ Normal — All settings at default"
        }

        tvCurrentMode.text = modeDisplay
        tvModeLog.text = if (log.isEmpty()) "No switches yet today."
        else log.joinToString("\n")
    }

    private fun resetPhoneMode() {
        axiomService?.resetPhoneMode()
        tvCurrentMode.text = "✅ Normal — Reset complete"
        Toast.makeText(this, "Phone mode reset to Normal", Toast.LENGTH_SHORT).show()
    }
}