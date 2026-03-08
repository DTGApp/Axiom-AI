package com.axiom.axiomnew

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContentProviderCompat.requireContext

// ════════════════════════════════════════════════════════════════════════════════
//  FileSearchActivity  —  Natural language file search UI
//
//  Fully programmatic layout — no activity_file_search.xml required.
//
//  Entry points:
//    • ⋮ menu → "Search Files" in MainActivity
//    • Voice: "show WhatsApp images yesterday" → auto-routed by AxiomService
//
//  UI:
//    ┌──────────────────────────────────────────────┐
//    │  [← back]   File Search                      │
//    ├──────────────────────────────────────────────┤
//    │  [🔍 Type a query e.g. "WhatsApp images..."] │
//    ├──────────────────────────────────────────────┤
//    │  [WhatsApp images today] [PDFs last week] …  │
//    ├──────────────────────────────────────────────┤
//    │  Status text / progress bar                  │
//    ├──────────────────────────────────────────────┤
//    │  Results list                                │
//    └──────────────────────────────────────────────┘
// ════════════════════════════════════════════════════════════════════════════════
class FileSearchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INITIAL_QUERY = "initial_query"

        private val SUGGESTIONS = listOf(
            "WhatsApp images today",
            "WhatsApp videos this week",
            "Photos from camera this month",
            "PDFs from last week",
            "Screenshots yesterday",
            "Telegram files this week",
            "Videos from last month",
            "Documents from 3 days ago"
        )

        private const val REQUEST_MEDIA_PERMISSION = 1001
    }

    private lateinit var fileSearch: FileSearchEngine

    // Views (created programmatically)
    private lateinit var etQuery:      EditText
    private lateinit var progressBar:  ProgressBar
    private lateinit var tvStatus:     TextView
    private lateinit var lvResults:    ListView
    private lateinit var chipScroll:   HorizontalScrollView
    private lateinit var permLayout:   LinearLayout
    private lateinit var btnSearch:    Button

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fileSearch = FileSearchEngine(this)

        setContentView(buildLayout())

        supportActionBar?.apply {
            title = "File Search"
            setDisplayHomeAsUpEnabled(true)
        }

        checkPermissionState()

        // Auto-run if launched with a query (from voice or AxiomService routing)
        intent.getStringExtra(EXTRA_INITIAL_QUERY)?.let { q ->
            etQuery.setText(q)
            if (FileSearchEngine.hasPermission(this)) runSearch(q)
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MEDIA_PERMISSION &&
            grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            checkPermissionState()
            tvStatus.text = "Permission granted! Try a search."
        }
    }

    // ── Programmatic layout ───────────────────────────────────────────────────

    private fun buildLayout(): View {
        val dp  = resources.displayMetrics.density
        val dpI = { n: Int -> (n * dp).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }

        // ── Search bar row ──────────────────────────────────────────────────
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpI(12), dpI(10), dpI(12), dpI(6))
            gravity = Gravity.CENTER_VERTICAL
        }

        etQuery = EditText(this).apply {
            hint      = "e.g. \"WhatsApp images from yesterday\""
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#888888"))
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(dpI(12), dpI(10), dpI(12), dpI(10))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType  = android.text.InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            textSize  = 15f
        }
        searchRow.addView(etQuery, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        btnSearch = Button(this).apply {
            text     = "Go"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2979FF"))
            setPadding(dpI(16), 0, dpI(16), 0)
        }
        searchRow.addView(btnSearch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dpI(44)).apply { leftMargin = dpI(8) })
        root.addView(searchRow)

        // ── Permission notice (hidden when permission is granted) ────────────
        permLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpI(16), dpI(8), dpI(16), dpI(8))
            visibility  = View.GONE
        }
        val tvPerm = TextView(this).apply {
            text      = "Axiom needs access to your media files to search them.\n" +
                    "No data ever leaves your phone — everything is on-device."
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize  = 13f
        }
        val btnGrant = Button(this).apply {
            text = "Grant File Access"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2979FF"))
            setOnClickListener { requestMediaPermission() }
        }
        permLayout.addView(tvPerm)
        permLayout.addView(btnGrant, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(permLayout)

        // ── Suggestion chips (horizontal scroll) ─────────────────────────────
        chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dpI(8), 0, dpI(8), dpI(8))
        }
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        SUGGESTIONS.forEach { suggestion ->
            val chip = TextView(this).apply {
                text   = suggestion
                setTextColor(Color.parseColor("#E0E0E0"))
                setBackgroundColor(Color.parseColor("#2A2A2A"))
                setPadding(dpI(14), dpI(8), dpI(14), dpI(8))
                textSize = 13f
                setOnClickListener {
                    etQuery.setText(suggestion)
                    if (FileSearchEngine.hasPermission(this@FileSearchActivity)) runSearch(suggestion)
                }
            }
            chipRow.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dpI(8) })
        }
        chipScroll.addView(chipRow)
        root.addView(chipScroll)

        // ── Status text ────────────────────────────────────────────────────
        tvStatus = TextView(this).apply {
            text  = "Enter a search or tap a suggestion above."
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 13f
            setPadding(dpI(16), dpI(4), dpI(16), dpI(4))
        }
        root.addView(tvStatus)

        // ── Progress bar ──────────────────────────────────────────────────
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility      = View.GONE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpI(4)))

        // ── Results list ──────────────────────────────────────────────────
        lvResults = ListView(this).apply {
            divider            = null
            dividerHeight      = 0
            setBackgroundColor(Color.parseColor("#121212"))
        }
        root.addView(lvResults, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── Wire interactions ─────────────────────────────────────────────
        etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val q = etQuery.text.toString().trim()
                if (q.isNotEmpty()) runSearch(q)
                true
            } else false
        }
        btnSearch.setOnClickListener {
            val q = etQuery.text.toString().trim()
            if (q.isNotEmpty()) runSearch(q)
        }

        return root
    }

    // ── Permission state ───────────────────────────────────────────────────────

    private fun checkPermissionState() {
        if (FileSearchEngine.hasPermission(this)) {
            permLayout.visibility  = View.GONE
            chipScroll.visibility  = View.VISIBLE
            etQuery.isEnabled      = true
            btnSearch.isEnabled    = true
        } else {
            permLayout.visibility  = View.VISIBLE
            chipScroll.visibility  = View.GONE
            etQuery.isEnabled      = false
            btnSearch.isEnabled    = false
            tvStatus.text          = "Storage permission needed."
        }
    }

    private fun requestMediaPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO
            ), REQUEST_MEDIA_PERMISSION)
        } else {
            requestPermissions(
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
                REQUEST_MEDIA_PERMISSION
            )
        }
    }

    // ── Search execution ───────────────────────────────────────────────────────

    private fun runSearch(query: String) {
        hideKeyboard()
        progressBar.visibility = View.VISIBLE
        tvStatus.text          = "Searching…"
        lvResults.adapter      = null
        btnSearch.isEnabled    = false

        Thread {
            val result = fileSearch.search(query)
            runOnUiThread {
                progressBar.visibility = View.GONE
                btnSearch.isEnabled    = true
                tvStatus.text          = result.summary
                if (result.files.isNotEmpty()) {
                    lvResults.adapter = FileResultAdapter(result.files)
                }
            }
        }.start()
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(etQuery.windowToken, 0)
    }

    // ── Results adapter ────────────────────────────────────────────────────────

    inner class FileResultAdapter(
        private val files: List<FileSearchEngine.FileResult>
    ) : android.widget.BaseAdapter() {

        private val dp   = resources.displayMetrics.density
        private val dpI  = { n: Int -> (n * dp).toInt() }

        override fun getCount()                = files.size
        override fun getItem(pos: Int)         = files[pos]
        override fun getItemId(pos: Int)       = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: android.view.ViewGroup?): View {
            val file = files[pos]

            val row = LinearLayout(this@FileSearchActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dpI(12), dpI(10), dpI(12), dpI(10))
                gravity     = Gravity.CENTER_VERTICAL
                setBackgroundColor(if (pos % 2 == 0) Color.parseColor("#1A1A1A")
                else Color.parseColor("#1E1E1E"))
                setOnClickListener { openFile(file) }
            }

            // Icon
            val icon = TextView(this@FileSearchActivity).apply {
                text     = when (file.fileType) {
                    FileSearchEngine.FileType.IMAGE    -> "🖼️"
                    FileSearchEngine.FileType.VIDEO    -> "🎬"
                    FileSearchEngine.FileType.AUDIO    -> "🎵"
                    FileSearchEngine.FileType.DOCUMENT -> "📄"
                    else                              -> "📁"
                }
                textSize = 22f
                gravity  = Gravity.CENTER
            }
            row.addView(icon, LinearLayout.LayoutParams(dpI(44), dpI(44)))

            // Text column
            val textCol = LinearLayout(this@FileSearchActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpI(10), 0, 0, 0)
            }

            val tvName = TextView(this@FileSearchActivity).apply {
                text      = file.displayName
                setTextColor(Color.WHITE)
                textSize  = 14f
                maxLines  = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(null, Typeface.BOLD)
            }

            val srcLabel = when (file.sourceApp) {
                FileSearchEngine.SourceApp.WHATSAPP          -> "📱 WhatsApp"
                FileSearchEngine.SourceApp.WHATSAPP_BUSINESS -> "💼 WA Business"
                FileSearchEngine.SourceApp.TELEGRAM          -> "✈️ Telegram"
                FileSearchEngine.SourceApp.CAMERA            -> "📷 Camera"
                FileSearchEngine.SourceApp.DOWNLOADS         -> "⬇️ Downloads"
                FileSearchEngine.SourceApp.SCREENSHOTS       -> "🖥️ Screenshot"
                else                                         -> ""
            }

            val tvMeta = TextView(this@FileSearchActivity).apply {
                text      = "${file.friendlyDate}  •  ${file.friendlySize}" +
                        (if (srcLabel.isNotEmpty()) "  •  $srcLabel" else "")
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize  = 12f
            }

            textCol.addView(tvName)
            textCol.addView(tvMeta)
            row.addView(textCol, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            return row
        }
    }

    // ── File open ─────────────────────────────────────────────────────────────

    private fun openFile(file: FileSearchEngine.FileResult) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(file.uri, file.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure {
            Toast.makeText(this, "Cannot open ${file.displayName}", Toast.LENGTH_SHORT).show()
        }
    }
}