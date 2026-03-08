package com.axiom.axiomnew

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

// ════════════════════════════════════════════════════════════════════════════
//  OnboardingActivity — First-launch journey screen
//
//  Shown once, the first time the user opens Axiom after setup.
//  Its job is to set one expectation clearly:
//    "This gets better over 7 days. Here's exactly what happens."
//
//  On MIUI/HyperOS devices (Xiaomi, Redmi, POCO), an extra section appears
//  with two deep-link buttons to remove battery restriction and enable
//  autostart — both required for the dream cycle to run overnight.
//
//  Triggered from: MainActivity.onCreate() if KEY_ONBOARDING_DONE is false.
//  After tapping "Let's go": sets KEY_ONBOARDING_DONE = true, finishes.
// ════════════════════════════════════════════════════════════════════════════
class OnboardingActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME          = "axiom_onboarding"
        const val KEY_ONBOARDING_DONE = "onboarding_done"

        fun shouldShow(ctx: Context): Boolean =
            !ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_ONBOARDING_DONE, false)

        fun markDone(ctx: Context) =
            ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()

        /**
         * Returns true when running on any MIUI/HyperOS ROM (Xiaomi, Redmi, POCO).
         * Checks manufacturer and brand — no reflection, no type ambiguity.
         */
        fun isMiui(): Boolean {
            val manufacturer = Build.MANUFACTURER.lowercase()
            val brand        = Build.BRAND.lowercase()
            val miuiBrands   = setOf("xiaomi", "redmi", "poco")
            return manufacturer in miuiBrands || brand in miuiBrands
        }
    }

    // ── Colours ───────────────────────────────────────────────────────────────
    private val colBg      = 0xFF0A0C12.toInt()
    private val colCard    = 0xFF141720.toInt()
    private val colAccent  = 0xFF00C8FF.toInt()
    private val colGreen   = 0xFF34D399.toInt()
    private val colMuted   = 0xFF6B7280.toInt()
    private val colText    = 0xFFE5E7EB.toInt()
    private val colSubtext = 0xFF9CA3AF.toInt()
    private val colDimLine = 0xFF1F2937.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(buildLayout())
    }

    // =========================================================================
    //  Layout
    // =========================================================================
    private fun buildLayout(): View {
        val root = ScrollView(this).apply {
            setBackgroundColor(colBg)
            isVerticalScrollBarEnabled = false
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(40))
        }

        // Title
        container.addView(label("Welcome to Axiom", 24, colText, Typeface.BOLD))
        container.addView(vspace(10))
        container.addView(label(
            "Most AI assistants answer questions. Axiom does something different: " +
                    "it learns how you use your phone and gets out of your way.",
            15, colSubtext
        ))
        container.addView(vspace(28))

        // One key expectation
        container.addView(calloutCard(
            "Important — read this before you start",
            "The first few times you use Axiom, it will feel like a normal assistant. " +
                    "That's because it is — it doesn't know you yet.\n\n" +
                    "Use it for 7 days. After that, something changes."
        ))
        container.addView(vspace(24))

        // Timeline
        container.addView(label("What happens over 7 days", 16, colText, Typeface.BOLD))
        container.addView(vspace(14))

        container.addView(timelineStep(
            day      = "Day 1-2",
            title    = "Works like any assistant",
            body     = "Type a command, get a result. Nothing magical yet. " +
                    "Axiom is quietly watching what you do and when.",
            dotColor = colMuted,
            isLast   = false
        ))
        container.addView(timelineStep(
            day      = "Day 3-4",
            title    = "First pattern recognised",
            body     = "Axiom starts noticing repetition. If you open the same app " +
                    "at the same time daily, it will predict it before you ask.",
            dotColor = colAccent,
            isLast   = false
        ))
        container.addView(timelineStep(
            day      = "Day 5-6",
            title    = "Proactive suggestions start",
            body     = "Axiom will notify you before you open the app yourself. " +
                    "You'll accept some, reject others. Both teach it.",
            dotColor = colAccent,
            isLast   = false
        ))
        container.addView(timelineStep(
            day      = "Day 7",
            title    = "Your first personal insight",
            body     = "Axiom will show you what it's learned: real numbers about " +
                    "your habits, patterns you didn't know existed.",
            dotColor = colGreen,
            isLast   = true
        ))

        container.addView(vspace(24))

        // Tips
        container.addView(label("Two things that speed up learning", 16, colText, Typeface.BOLD))
        container.addView(vspace(14))
        container.addView(tipRow(
            "1.",
            "When Axiom is wrong, use the label picker.",
            "Swipe away a bad suggestion and choose what you actually wanted. " +
                    "That's worth 10x a simple rejection."
        ))
        container.addView(vspace(10))
        container.addView(tipRow(
            "2.",
            "Let it run in the background.",
            "Axiom learns from your phone habits even when you're not typing commands. " +
                    "Don't restrict its battery."
        ))

        container.addView(vspace(32))

        // MIUI battery whitelist (Xiaomi / Redmi / POCO only)
        if (isMiui()) {
            addMiuiSection(container)
            container.addView(vspace(28))
        }

        // CTA
        val btnStart = Button(this).apply {
            text     = "Let's go"
            textSize = 16f
            setTextColor(colBg)
            backgroundTintList = android.content.res.ColorStateList.valueOf(colAccent)
            typeface   = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            )
            setOnClickListener {
                markDone(this@OnboardingActivity)
                finish()
            }
        }
        container.addView(btnStart)

        container.addView(vspace(16))
        container.addView(
            label(
                "Everything Axiom learns stays on your phone. No account, no cloud, no tracking.",
                12, colMuted
            ).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
        )

        root.addView(
            container,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return root
    }

    // -------------------------------------------------------------------------
    //  MIUI section — battery whitelist + autostart
    // -------------------------------------------------------------------------
    private fun addMiuiSection(container: LinearLayout) {
        container.addView(label(
            "One last thing (Xiaomi device)", 16, colText, Typeface.BOLD
        ))
        container.addView(vspace(10))
        container.addView(label(
            "MIUI aggressively restricts background apps. Without two quick steps, " +
                    "Axiom's dream cycle and proactive suggestions will never run.",
            13, colSubtext
        ))
        container.addView(vspace(14))

        // Step A: Battery restriction
        val btnBattery = Button(this).apply {
            text     = "1. Remove battery restriction"
            textSize = 14f
            setTextColor(colBg)
            backgroundTintList = android.content.res.ColorStateList.valueOf(colAccent)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            )
            setOnClickListener { openBatterySettings() }
        }
        container.addView(btnBattery)
        container.addView(vspace(8))

        // Step B: Autostart
        val btnAutostart = Button(this).apply {
            text     = "2. Enable autostart"
            textSize = 14f
            setTextColor(colAccent)
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF111D2A.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            )
            setOnClickListener { openAutostartSettings() }
        }
        container.addView(btnAutostart)

        container.addView(vspace(8))
        container.addView(label(
            "Battery: tap Axiom > Battery saver > No restrictions.\n" +
                    "Autostart: find Axiom in the list and toggle it on.",
            12, colMuted
        ))
    }

    // =========================================================================
    //  Components
    // =========================================================================

    private fun calloutCard(headline: String, body: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background  = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF1A1400.toInt())
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0xFF92400E.toInt())
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(label(headline, 14, 0xFFFBBF24.toInt(), Typeface.BOLD))
            addView(vspace(8))
            addView(label(body, 13, colSubtext))
        }

    private fun timelineStep(
        day: String, title: String, body: String,
        dotColor: Int, isLast: Boolean
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation  = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val spine = LinearLayout(this).apply {
            orientation  = LinearLayout.VERTICAL
            gravity      = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                dp(32), LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        val dot = android.view.View(this).apply {
            background   = android.graphics.drawable.GradientDrawable().apply {
                shape    = android.graphics.drawable.GradientDrawable.OVAL
                setColor(dotColor)
            }
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
        }
        spine.addView(dot)
        if (!isLast) {
            val line = android.view.View(this).apply {
                setBackgroundColor(colDimLine)
                layoutParams = LinearLayout.LayoutParams(
                    dp(2), LinearLayout.LayoutParams.MATCH_PARENT
                ).apply { topMargin = dp(4) }
            }
            spine.addView(line)
        }

        val content = LinearLayout(this).apply {
            orientation  = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, dp(20))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        content.addView(
            label(day, 11, colMuted, Typeface.BOLD).apply { letterSpacing = 0.08f }
        )
        content.addView(vspace(2))
        content.addView(label(title, 15, colText, Typeface.BOLD))
        content.addView(vspace(4))
        content.addView(label(body, 13, colSubtext))

        row.addView(spine)
        row.addView(content)
        return row
    }

    private fun tipRow(emoji: String, headline: String, detail: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background  = android.graphics.drawable.GradientDrawable().apply {
                setColor(colCard)
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(label(emoji, 22, colText).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dp(36), LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
            })
            val textCol = LinearLayout(this@OnboardingActivity).apply {
                orientation  = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                setPadding(dp(8), 0, 0, 0)
            }
            textCol.addView(label(headline, 14, colText, Typeface.BOLD))
            textCol.addView(vspace(3))
            textCol.addView(label(detail, 12, colSubtext))
            addView(textCol)
        }

    // =========================================================================
    //  Helpers
    // =========================================================================

    private fun label(
        text: String, size: Int, color: Int,
        style: Int = Typeface.NORMAL
    ) = TextView(this).apply {
        this.text    = text
        textSize     = size.toFloat()
        setTextColor(color)
        typeface     = Typeface.create(Typeface.DEFAULT, style)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun vspace(dp: Int) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(dp)
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // =========================================================================
    //  MIUI deep-link helpers
    // =========================================================================

    /**
     * Opens the battery optimisation exemption screen for Axiom.
     * On MIUI this maps to "Battery saver -> No restrictions".
     * Tries most-specific to least-specific intent in order.
     */
    private fun openBatterySettings() {
        // Try 1: Direct per-app battery optimisation request (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                ).apply { data = Uri.parse("package:$packageName") }
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    return
                }
            }
        }
        // Try 2: General battery optimisation list
        val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (listIntent.resolveActivity(packageManager) != null) {
            startActivity(listIntent)
            return
        }
        // Try 3: MIUI-specific power management
        try {
            startActivity(
                Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /**
     * Opens MIUI's Autostart settings. User must toggle Axiom on.
     * Falls back to app info page if the MIUI intent is unavailable.
     */
    private fun openAutostartSettings() {
        // Try 1: MIUI Security Center autostart list
        try {
            startActivity(
                Intent("miui.intent.action.OP_AUTO_START").apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
            )
            return
        } catch (e: Exception) { /* try next */ }

        // Try 2: Direct component — HyperOS / MIUI 14
        try {
            startActivity(
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                }
            )
            return
        } catch (e: Exception) { /* try next */ }

        // Fallback: App info page
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}