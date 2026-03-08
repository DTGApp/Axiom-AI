
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

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

// ════════════════════════════════════════════════════════════════════════════════
//  AiDetectorActivity  —  Detect AI-generated text or images
//
//  Fully programmatic layout — NO XML required.
//
//  Entry points:
//    • ⋮ menu → "🧬 AI Detector" in MainActivity
//    • Voice: "ai detector", "check if ai", "detect ai", "is this ai" → auto-routed
//    • Intent with EXTRA_PREFILL_TEXT to pre-load text
//
//  Tabs: 📝 Text | 🖼️ Image
// ════════════════════════════════════════════════════════════════════════════════
class AiDetectorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PREFILL_TEXT = "prefill_text"
        /** Pass 0 = Text tab, 1 = Image tab */
        const val EXTRA_OPEN_TAB     = "open_tab"
        private const val REQ_PICK_IMAGE = 2001
    }

    private lateinit var detector: AiContentDetector

    // Views
    private lateinit var tabText:         TextView
    private lateinit var tabImage:        TextView
    private lateinit var textPanel:       LinearLayout
    private lateinit var imagePanel:      LinearLayout
    private lateinit var etInputText:     EditText
    private lateinit var tvWordCount:     TextView
    private lateinit var btnAnalyseText:  Button
    private lateinit var btnClearText:    Button
    private lateinit var ivPreview:       ImageView
    private lateinit var tvImageName:     TextView
    private lateinit var btnPickImage:    Button
    private lateinit var btnAnalyseImage: Button
    private lateinit var progressBar:     ProgressBar
    private lateinit var resultCard:      LinearLayout
    private lateinit var tvVerdict:       TextView
    private lateinit var tvConfidence:    TextView
    private lateinit var tvSummary:       TextView
    private lateinit var llSignals:       LinearLayout
    private lateinit var scrollView:      ScrollView

    private var selectedUri: Uri? = null
    private var activeTab = 0

    private val dp   by lazy { resources.displayMetrics.density }
    private fun dp(n: Int) = (n * dp).toInt()

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        detector = AiContentDetector(this)
        setContentView(buildLayout())
        supportActionBar?.apply {
            title = "AI Content Detector"
            setDisplayHomeAsUpEnabled(true)
        }
        // Determine which tab to open — default Text (0), pass 1 for Image tab
        val openTab = intent.getIntExtra(EXTRA_OPEN_TAB, 0)
        showTab(openTab)

        intent.getStringExtra(EXTRA_PREFILL_TEXT)?.let { text ->
            etInputText.setText(text)
            showTab(0)   // text prefill always opens text tab
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_IMAGE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            selectedUri = uri
            tvImageName.text  = getDisplayName(uri)
            ivPreview.setImageURI(uri)
            ivPreview.visibility      = View.VISIBLE
            btnAnalyseImage.isEnabled = true
            resultCard.visibility     = View.GONE
        }
    }

    // ── Layout ─────────────────────────────────────────────────────────────────

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }

        // Tab bar
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
        }
        tabText  = makeTab("📝  Text")
        tabImage = makeTab("🖼️  Image")
        tabText.setOnClickListener  { showTab(0) }
        tabImage.setOnClickListener { showTab(1) }
        tabBar.addView(tabText,  LinearLayout.LayoutParams(0, dp(46), 1f))
        tabBar.addView(tabImage, LinearLayout.LayoutParams(0, dp(46), 1f))
        root.addView(tabBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))

        // Scroll container
        scrollView = ScrollView(this)
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(20))
        }

        // ── TEXT PANEL ─────────────────────────────────────────────────────────
        textPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val tvHint1 = makeHint("Paste any text to check if it was written by AI.\n" +
                "Works best with 50+ words. The on-device LLM will also analyse it if loaded.")

        etInputText = EditText(this).apply {
            hint     = "Paste text here…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#555555"))
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minLines  = 8
            maxLines  = 20
            gravity   = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            textSize  = 14f
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val wc = s.toString().trim()
                        .split(Regex("\\s+")).count { it.isNotEmpty() }
                    tvWordCount.text       = "$wc words"
                    btnAnalyseText.isEnabled = wc >= 10
                }
            })
        }

        tvWordCount = TextView(this).apply {
            text = "0 words"
            setTextColor(Color.parseColor("#666666"))
            textSize = 11f
            gravity  = Gravity.END
            setPadding(0, dp(4), 0, dp(10))
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
        }
        btnClearText = makeButton("Clear", "#2A2A2A", "#AAAAAA")
        btnClearText.setOnClickListener {
            etInputText.text.clear()
            resultCard.visibility = View.GONE
        }
        btnAnalyseText = makeButton("🔍  Analyse Text", "#3D5AFE", "#FFFFFF")
        btnAnalyseText.isEnabled = false
        btnAnalyseText.setOnClickListener { runTextAnalysis() }

        btnRow.addView(btnClearText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply { rightMargin = dp(8) })
        btnRow.addView(btnAnalyseText, LinearLayout.LayoutParams(0, dp(44), 1f))

        textPanel.addView(tvHint1)
        textPanel.addView(etInputText)
        textPanel.addView(tvWordCount)
        textPanel.addView(btnRow)

        // ── IMAGE PANEL ────────────────────────────────────────────────────────
        imagePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val tvHint2 = makeHint(
            "Select an image to check if it was generated by AI (Midjourney, DALL·E, " +
                    "Stable Diffusion, Firefly, etc.).\n\n" +
                    "Detection uses file metadata, dimensions, and colour patterns. " +
                    "100% on-device — no internet needed."
        )

        btnPickImage = makeButton("📂  Pick Image from Gallery", "#3D5AFE", "#FFFFFF")
        btnPickImage.setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_PICK).apply { type = "image/*" },
                REQ_PICK_IMAGE
            )
        }

        ivPreview = ImageView(this).apply {
            scaleType   = ImageView.ScaleType.CENTER_CROP
            visibility  = View.GONE
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }

        tvImageName = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#888888"))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(6))
        }

        btnAnalyseImage = makeButton("🔍  Analyse Image", "#3D5AFE", "#FFFFFF")
        btnAnalyseImage.isEnabled = false
        btnAnalyseImage.setOnClickListener { runImageAnalysis() }

        imagePanel.addView(tvHint2)
        imagePanel.addView(btnPickImage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(12) })
        imagePanel.addView(ivPreview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(220)).apply { bottomMargin = dp(4) })
        imagePanel.addView(tvImageName)
        imagePanel.addView(btnAnalyseImage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        // ── PROGRESS ───────────────────────────────────────────────────────────
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility      = View.GONE
        }

        // ── RESULT CARD ────────────────────────────────────────────────────────
        resultCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE
        }

        tvVerdict = TextView(this).apply {
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        tvConfidence = TextView(this).apply {
            setTextColor(Color.parseColor("#888888"))
            textSize = 13f
            gravity  = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(12))
        }
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#2E2E2E"))
        }
        tvSummary = TextView(this).apply {
            setTextColor(Color.parseColor("#DDDDDD"))
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
        }
        val tvSignalsHeader = TextView(this).apply {
            text = "Detection Signals"
            setTextColor(Color.parseColor("#666666"))
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            isAllCaps = true
            setPadding(0, dp(4), 0, dp(8))
        }
        llSignals = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val tvDisclaimer = TextView(this).apply {
            text = "⚠️  AI detection is probabilistic — treat results as one indicator, not definitive proof."
            setTextColor(Color.parseColor("#555555"))
            textSize = 11f
            setPadding(0, dp(16), 0, 0)
        }

        resultCard.addView(tvVerdict)
        resultCard.addView(tvConfidence)
        resultCard.addView(divider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1))
        resultCard.addView(tvSummary)
        resultCard.addView(tvSignalsHeader)
        resultCard.addView(llSignals)
        resultCard.addView(tvDisclaimer)

        // Assemble scroll content
        scrollContent.addView(textPanel)
        scrollContent.addView(imagePanel)
        scrollContent.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(12) })
        scrollContent.addView(resultCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        scrollView.addView(scrollContent)
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        return root
    }

    // ── Tab switching ──────────────────────────────────────────────────────────

    private fun showTab(tab: Int) {
        activeTab             = tab
        textPanel.visibility  = if (tab == 0) View.VISIBLE else View.GONE
        imagePanel.visibility = if (tab == 1) View.VISIBLE else View.GONE
        resultCard.visibility = View.GONE

        listOf(tabText to 0, tabImage to 1).forEach { (view, idx) ->
            val active = idx == tab
            view.setTextColor(if (active) Color.WHITE else Color.parseColor("#777777"))
            view.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            view.setBackgroundColor(
                if (active) Color.parseColor("#262626")
                else        Color.parseColor("#1A1A1A"))
        }
    }

    // ── Analysis ───────────────────────────────────────────────────────────────

    private fun runTextAnalysis() {
        val text = etInputText.text.toString().trim()
        if (text.isEmpty()) return
        hideKeyboard()
        setLoading(true)
        Thread {
            val result = detector.analyseText(text)
            runOnUiThread { setLoading(false); showResult(result) }
        }.start()
    }

    private fun runImageAnalysis() {
        val uri = selectedUri ?: return
        setLoading(true)
        Thread {
            val result = detector.analyseImage(uri)
            runOnUiThread { setLoading(false); showResult(result) }
        }.start()
    }

    // ── Result display ─────────────────────────────────────────────────────────

    private fun showResult(result: AiContentDetector.DetectionResult) {
        resultCard.visibility = View.VISIBLE
        tvVerdict.text        = result.verdictLabel
        tvVerdict.setTextColor(result.verdictColor)
        tvConfidence.text     = if (result.confidence > 0) "Confidence: ${result.confidence}%" else ""
        tvSummary.text        = result.summary

        llSignals.removeAllViews()
        result.signals.filter { it.weight > 0f }.forEach { signal ->
            llSignals.addView(buildSignalRow(signal))
        }

        // Scroll to result after layout
        scrollView.post {
            scrollView.smoothScrollTo(0, resultCard.top)
        }
    }

    private fun buildSignalRow(s: AiContentDetector.Signal): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(7), 0, dp(7))
        }
        val icon = TextView(this).apply {
            text     = if (s.pointsToAi) "🔴" else "🟢"
            textSize = 12f
            gravity  = Gravity.TOP
            setPadding(0, dp(2), dp(10), 0)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = s.name
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = s.description
            setTextColor(Color.parseColor("#999999"))
            textSize = 12f
        })
        row.addView(icon, LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(col,  LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(row)
        wrapper.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#222222"))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        return wrapper
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun setLoading(loading: Boolean) {
        progressBar.visibility    = if (loading) View.VISIBLE else View.GONE
        btnAnalyseText.isEnabled  = !loading
        btnAnalyseImage.isEnabled = !loading && selectedUri != null
        if (loading) resultCard.visibility = View.GONE
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etInputText.windowToken, 0)
    }

    private fun getDisplayName(uri: Uri): String {
        var name = ""
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx) ?: ""
                }
            }
        }
        return name.ifEmpty { uri.lastPathSegment ?: "Selected image" }
    }

    private fun makeTab(label: String) = TextView(this).apply {
        text     = label
        textSize = 14f
        gravity  = Gravity.CENTER
        setTextColor(Color.parseColor("#777777"))
        isClickable = true; isFocusable = true
    }

    private fun makeButton(label: String, bgHex: String, fgHex: String) =
        Button(this).apply {
            text     = label
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.parseColor(fgHex))
            setBackgroundColor(Color.parseColor(bgHex))
        }

    private fun makeHint(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#888888"))
        textSize  = 13f
        setPadding(0, 0, 0, dp(12))
    }
}