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

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.util.Calendar

// ════════════════════════════════════════════════════════════════════════════════
//  MainActivity - UI shell only. v0.3
//
//  New in v0.3:
//    • Seed model download via DownloadManager (cloud URL, no storage perm needed)
//    • Full permission flow: POST_NOTIFICATIONS, BLUETOOTH_CONNECT, Usage Access
//    • Permission card shown until all required permissions are granted
//    • Seed card shown until model file is present
//    • Proactive button removed from UI (service runs it automatically every 15 min)
//    • Result card hidden until first inference
//    • Event Viewer accessible via ⋮ button top-right
// ════════════════════════════════════════════════════════════════════════════════
class MainActivity : AppCompatActivity() {

    companion object {
        const val CHANNEL_ID     = "axiom_proactive"
        const val NOTIF_ID       = AxiomService.NOTIF_PROACTIVE_ID
        const val MIN_CONFIDENCE = 0.50f

        // ── SEED DOWNLOAD URL ─────────────────────────────────────────────────
        // Replace this with your actual hosted model URL.
        // The file will be downloaded via DownloadManager and moved to filesDir.
        const val SEED_DOWNLOAD_URL = "https://ourcityapp.cloud/gguf/axiom_seed_q4.gguf"
        const val SEED_FILENAME     = "axiom_seed_q4.gguf"
        // SharedPrefs keys for persisting download state across lock/restart
        private const val PREFS_DOWNLOAD       = "axiom_download"
        private const val PREF_DOWNLOAD_ID     = "download_id"
    }

    // ── Service binding ─────────────────────────────────────────────────────
    private var axiomService: AxiomService? = null
    private var serviceBound  = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val readyPoller = object : Runnable {
        override fun run() {
            val svc = axiomService ?: return
            if (svc.isReady()) {
                tvStatus.text = "✅  Axiom Ready"
                // Engine confirmed ready — update learning bar so it shows
                // "ready" instead of "initialising"
                refreshLearningBar()
            } else {
                uiHandler.postDelayed(this, 500)
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            axiomService = (binder as AxiomService.LocalBinder).getService()
            serviceBound = true
            onServiceReady()
            // Update header dot — service is live
            runOnUiThread {
                findViewById<android.view.View?>(R.id.viewServiceDot)
                    ?.setBackgroundColor(0xFF00E5FF.toInt())
                findViewById<android.widget.TextView?>(R.id.tvServiceStatus)
                    ?.text = "On-device · Service running"
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            uiHandler.removeCallbacks(readyPoller)
            axiomService = null
            serviceBound = false
            runOnUiThread {
                findViewById<android.view.View?>(R.id.viewServiceDot)
                    ?.setBackgroundColor(0xFF2E4257.toInt())
                findViewById<android.widget.TextView?>(R.id.tvServiceStatus)
                    ?.text = "On-device · Reconnecting..."
            }
        }
    }

    // ── UI views ────────────────────────────────────────────────────────────
    private lateinit var tvStatus:          TextView
    private lateinit var inputField:        EditText
    private lateinit var btnExecute:        Button
    private lateinit var btnProactive:      Button          // hidden, kept for API compat
    private lateinit var tvLastAction:      TextView
    private lateinit var tvConfidence:      TextView
    private lateinit var cardLastResult:    View
    private var greetingSection:         View? = null
    private lateinit var cardPermissions:   View
    private lateinit var tvPermissionHint:  TextView
    private lateinit var btnGrantPerms:     Button
    private lateinit var cardSeedDownload:  View
    private lateinit var btnDownloadSeed:   Button
    private lateinit var btnCancelDownload: Button
    private lateinit var downloadProgress:  ProgressBar
    private lateinit var tvDownloadStatus:  TextView

    // ── Context card views (nullable - safe if layout doesn't have them) ───────
    private var cardContext:        View?     = null
    private var tvContextGreeting:  TextView? = null
    private var tvContextTime:      TextView? = null
    private var tvContextHeadline:  TextView? = null
    private var tvContextSupport:   TextView? = null
    private var tvContextFooter:    TextView? = null
    private var btnContextAction:   Button?   = null
    private var viewContextAccent:  View?     = null
    private var contextCardEngine: ContextCardEngine? = null

    // ── Learning bar ──────────────────────────────────────────────────────────
    private var cardLearningBar:   View?     = null
    private var viewLearningDot:   View?     = null
    private var tvLearningStatus:  TextView? = null
    private var tvLearningBadge:   TextView? = null

    // ── World model card ──────────────────────────────────────────────────────
    private var cardWorldModel:             View?     = null
    private var tvWorldModelSummary:        TextView? = null
    private var tvWorldModelToggle:         TextView? = null
    private var layoutWorldModelDetail:     View?     = null
    private var tvWorldModelDetail:         TextView? = null
    private var tvWorldModelFooter:         TextView? = null
    private var worldModelExpanded = false
    // Yesterday section — shown on day 1 before any learning, populated from UsageStats
    private var layoutWorldModelYesterday:  View?     = null
    private var tvYesterdayApp1:            TextView? = null
    private var tvYesterdayApp2:            TextView? = null
    private var tvYesterdayApp3:            TextView? = null

    // ── Main scroll view — used to auto-scroll to dream card ────────────────
    private var mainScrollView: android.widget.ScrollView? = null

    // ── Dream card ────────────────────────────────────────────────────────────
    private var cardDream:            View?                 = null
    private var layoutDreamActive:    View?                 = null
    private var layoutDreamComplete:  View?                 = null
    private var layoutDreamMissed:    View?                 = null
    private var dreamAnimation:       DreamingAnimationView? = null
    private var tvExamplePrimary:     android.widget.TextView? = null
    private var tvExampleSecondary1:  android.widget.TextView? = null
    private var tvExampleSecondary2:  android.widget.TextView? = null
    private var exampleSetIndex:      Int = -1   // -1 = not yet set; increments each onResume
    private var tvDreamCompleteTime:  TextView?             = null
    private var tvDreamInteractions:  TextView?             = null
    private var tvDreamPatterns:      TextView?             = null
    private var tvDreamRemoved:       TextView?             = null
    private var tvDreamHabits:        TextView?             = null
    private var btnDreamDismiss:      Button?               = null
    private var tvDreamMissedReason:  TextView?             = null
    private var tvDreamMissedDays:    TextView?             = null
    private var dreamReceiver:        android.content.BroadcastReceiver? = null
    private var dreamAnimStartMs:     Long = 0L   // timestamp when dreaming animation started

    // ── On-device moment card ─────────────────────────────────────────────────
    private var cardOnDeviceMoment:  View?     = null
    private var tvOnDeviceMs:        TextView? = null
    private var tvOnDeviceClock:     TextView? = null
    private var btnOnDeviceDismiss:  Button?   = null
    // Clock ticker — runs every second while the on-device moment card is visible
    private val clockHandler  = android.os.Handler(android.os.Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            tvOnDeviceClock?.text = fmt.format(java.util.Date())
            clockHandler.postDelayed(this, 1000L)
        }
    }

    // Prefs
    private val PREFS_MAIN = "axiom_main"

    private var lastIntent = ""
    private var lastUserPrompt = ""  // stored before each inference for web fallback

    // Debounce: MIUI SpeechRecognizer fires onResults twice - gap can be 14-19s.
    // Time-only debounce (5s) regardless of prompt text - recognizer may return
    // slightly different strings for the same utterance ("battery low" vs "battery is low").
    private var lastInferenceTs = 0L
    // Generation counter: discards stale LLM threads that post results after
    // a newer call has already completed.
    @Volatile private var inferGeneration = 0
    // In-flight guard: prevents queuing a second infer() call while one is running.
    @Volatile private var inferInFlight = false

    // ── Voice input ─────────────────────────────────────────────────────────
    private lateinit var btnMic: ImageButton

    // ── TTS - Android built-in Text-to-Speech ────────────────────────────────
    // No extra model, no internet. Uses whatever TTS engine is installed on device.
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false

    // True when the current inference was triggered by the mic button (not typing).
    // TTS reply only fires in voice mode - silent when user typed.
    private var voiceTriggered = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    // ── Download tracking ────────────────────────────────────────────────────
    private var downloadId: Long = -1L
    private val downloadPollRunnable = object : Runnable {
        override fun run() { pollDownloadProgress() }
    }

    // ── Permission launchers ─────────────────────────────────────────────────
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) createNotificationChannel()
        updatePermissionCard()
    }

    private val bluetoothPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updatePermissionCard() }

    // RECORD_AUDIO - needed for SpeechRecognizer.
    // Requested only when user taps the mic button, not at startup.
    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceInput()
        else Toast.makeText(this,
            "Microphone permission needed for voice input",
            Toast.LENGTH_SHORT).show()
    }

    // READ_MEDIA_* / READ_EXTERNAL_STORAGE - for FileSearchEngine.
    private val mediaPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it }) {
            startActivity(Intent(this, FileSearchActivity::class.java))
        } else {
            Toast.makeText(this, "Storage permission needed to search files",
                Toast.LENGTH_SHORT).show()
        }
    }

    // READ_CALENDAR - for LifeContextAssistant proactive reminders.
    private val calendarPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        android.util.Log.i("AxiomMain", "Calendar permission granted=$granted")
    }

    // ════════════════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()   // we use a custom top bar in the XML

        bindViews()
        createNotificationChannel()
        initTts()

        // Show onboarding on first launch
        if (OnboardingActivity.shouldShow(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        // Start service immediately - runs even when app is closed
        val svcIntent = Intent(this, AxiomService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(svcIntent)
        else
            startService(svcIntent)

        bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        scheduleDreamingCycle()
        ChargingReceiver.scheduleDailyAlarms(this)

        // Request runtime permissions after a short delay so UI is visible first
        uiHandler.postDelayed({ requestRuntimePermissions() }, 800)
    }

    override fun onResume() {
        super.onResume()
        if (!serviceBound) {
            bindService(Intent(this, AxiomService::class.java),
                serviceConnection, Context.BIND_AUTO_CREATE)
        }
        updatePermissionCard()
        updateSeedCard()
        // Auto-reconnect UI to any download that was running before screen locked
        reconnectDownloadIfActive()
        // Refresh the context card every time the app comes to foreground
        refreshContextCard()
        refreshLearningBar()
        refreshWorldModelCard()
        updateDreamCard()
        registerDreamReceiver()
        updateExampleCard()
    }

    // ── Context card ──────────────────────────────────────────────────────────

    /**
     * Builds and renders the live context card on a background thread.
     * Safe to call on every onResume - completes in < 100ms.
     * Only updates the card when greetingSection is visible (i.e. no active
     * response card is showing).
     */
    private fun refreshContextCard() {
        val engine = contextCardEngine ?: return
        Thread {
            val card = runCatching { engine.build() }.getOrNull() ?: return@Thread
            runOnUiThread { applyContextCard(card) }
        }.start()
    }

    private fun applyContextCard(card: ContextCardEngine.ContextCard) {
        // All accesses are null-safe - context card may not be in every layout version
        val sdf = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        tvContextTime?.text     = sdf.format(java.util.Date())
        tvContextGreeting?.text = card.greeting.uppercase()
        tvContextHeadline?.text = card.headline
        tvContextSupport?.text  = card.supportLine
        tvContextFooter?.text   = card.footerLine

        viewContextAccent?.setBackgroundColor(card.accentColor)

        btnContextAction?.backgroundTintList =
            android.content.res.ColorStateList.valueOf(card.accentColor)

        val btn = btnContextAction ?: return
        if (card.actionLabel != null && card.actionPayload != null) {
            btn.text       = card.actionLabel
            btn.visibility = View.VISIBLE
            btn.setOnClickListener {
                handleContextAction(card.actionPayload, card.actionLabel)
            }
        } else {
            btn.visibility = View.GONE
        }
    }

    /**
     * Called when the user taps the context card action button.
     * Payload is either a package name (app launch) or an ACTION_* string.
     */
    private fun handleContextAction(payload: String, label: String) {
        cardContext?.visibility = View.GONE
        cardLastResult.visibility   = View.VISIBLE
        tvLastAction.text           = "Opening $label..."
        tvConfidence.text           = "⚡ Predicted"

        // Package name → direct launch
        val launchIntent = runCatching { packageManager.getLaunchIntentForPackage(payload) }.getOrNull()
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(launchIntent) }
            axiomService?.feedback("APP:$payload", true)
            return
        }
        // ACTION_* string → run through normal intent dispatch
        launchIntent(payload, "")
        axiomService?.feedback(payload, true)
    }

    /**
     * If DownloadManager has an active download for our ID (phone was locked mid-download),
     * restore the progress UI and resume polling - no bytes are re-downloaded.
     */
    private fun reconnectDownloadIfActive() {
        if (downloadId != -1L) return   // already tracking
        val savedId = getSharedPreferences(PREFS_DOWNLOAD, MODE_PRIVATE)
            .getLong(PREF_DOWNLOAD_ID, -1L)
        if (savedId == -1L) return

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(savedId))?.use { c ->
            if (c.moveToFirst()) {
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                when (status) {
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_PAUSED -> {
                        downloadId = savedId
                        btnDownloadSeed.isEnabled    = false
                        btnCancelDownload.visibility  = View.VISIBLE
                        downloadProgress.visibility   = View.VISIBLE
                        tvDownloadStatus.visibility   = View.VISIBLE
                        tvDownloadStatus.text         = "Resuming..."
                        uiHandler.post(downloadPollRunnable)
                        android.util.Log.i("AxiomDownload", "UI reconnected to download $savedId after screen unlock")
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        // Completed while screen was locked - handle it now
                        onDownloadComplete()
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        onDownloadFailed("Error $reason")
                    }
                    else -> {
                        // Unknown state - clear the stale ID
                        getSharedPreferences(PREFS_DOWNLOAD, MODE_PRIVATE).edit()
                            .remove(PREF_DOWNLOAD_ID).apply()
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Dream receiver intentionally NOT unregistered here.
        // onPause fires for notification shade, permission dialogs, multitasking
        // preview — all of which drop the receiver before the dream broadcast fires.
        // Moved to onStop() which only fires when the activity is fully off-screen.
        uiHandler.removeCallbacks(readyPoller)
        uiHandler.removeCallbacks(downloadPollRunnable)
    }

    override fun onStop() {
        super.onStop()
        unregisterDreamReceiver()
    }

    override fun onDestroy() {
        clockHandler.removeCallbacks(clockRunnable)
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            axiomService = null
        }
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.shutdown()
        tts = null
        // Foreground service keeps running - do NOT stop it here
    }

    // ── Keyboard helpers ──────────────────────────────────────────────────────

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(inputField.windowToken, 0)
        inputField.clearFocus()
    }

    /**
     * Called at the start of inference: hides keyboard, disables both input
     * buttons so the user cannot queue a second request.
     * Called again at the end to restore everything.
     */
    private fun setProcessingState(isProcessing: Boolean) {
        if (isProcessing) {
            hideKeyboard()
            btnExecute.isEnabled = false
            btnMic.isEnabled     = false
            btnExecute.alpha     = 0.45f
            btnMic.alpha         = 0.45f
        } else {
            btnExecute.isEnabled = true
            btnMic.isEnabled     = true
            btnExecute.alpha     = 1.0f
            btnMic.alpha         = 1.0f
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  View wiring
    // ════════════════════════════════════════════════════════════════════════
    private fun bindViews() {
        tvStatus         = findViewById(R.id.tvStatus)
        inputField       = findViewById(R.id.editPrompt)
        btnExecute       = findViewById(R.id.btnInfer)
        btnProactive     = findViewById(R.id.btnProactive)      // hidden
        tvLastAction     = findViewById(R.id.tvLastAction)
        tvConfidence     = findViewById(R.id.tvConfidence)
        cardLastResult   = findViewById(R.id.cardLastResult)
        cardPermissions  = findViewById(R.id.cardPermissions)
        tvPermissionHint = findViewById(R.id.tvPermissionHint)
        btnGrantPerms    = findViewById(R.id.btnGrantPermissions)
        cardSeedDownload = findViewById(R.id.cardSeedDownload)
        btnDownloadSeed  = findViewById(R.id.btnDownloadSeed)
        btnCancelDownload= findViewById(R.id.btnCancelDownload)
        downloadProgress = findViewById(R.id.downloadProgress)
        tvDownloadStatus = findViewById(R.id.tvDownloadStatus)

        // Context card views - safe lookup, null if not in layout
        cardContext       = findViewById<View>(R.id.cardContext)
        tvContextGreeting = findViewById<TextView>(R.id.tvContextGreeting)
        tvContextTime     = findViewById<TextView>(R.id.tvContextTime)
        tvContextHeadline = findViewById<TextView>(R.id.tvContextHeadline)
        tvContextSupport  = findViewById<TextView>(R.id.tvContextSupport)
        tvContextFooter   = findViewById<TextView>(R.id.tvContextFooter)
        btnContextAction  = findViewById<Button>(R.id.btnContextAction)
        viewContextAccent = findViewById<View>(R.id.viewContextAccent)

        contextCardEngine = ContextCardEngine(this)

        // Learning bar
        cardLearningBar  = findViewById(R.id.cardLearningBar)
        viewLearningDot  = findViewById(R.id.viewLearningDot)
        tvLearningStatus = findViewById(R.id.tvLearningStatus)
        tvLearningBadge  = findViewById(R.id.tvLearningBadge)

        // World model card
        cardWorldModel         = findViewById(R.id.cardWorldModel)
        tvWorldModelSummary    = findViewById(R.id.tvWorldModelSummary)
        tvWorldModelToggle     = findViewById(R.id.tvWorldModelToggle)
        layoutWorldModelDetail = findViewById(R.id.layoutWorldModelDetail)
        tvWorldModelDetail     = findViewById(R.id.tvWorldModelDetail)
        tvWorldModelFooter     = findViewById(R.id.tvWorldModelFooter)
        cardWorldModel?.setOnClickListener { toggleWorldModel() }

        // On-device moment card
        cardOnDeviceMoment      = findViewById(R.id.cardOnDeviceMoment)
        tvOnDeviceMs            = findViewById(R.id.tvOnDeviceMs)
        tvOnDeviceClock         = findViewById(R.id.tvOnDeviceClock)
        btnOnDeviceDismiss      = findViewById(R.id.btnOnDeviceDismiss)
        // World model — yesterday section
        layoutWorldModelYesterday = findViewById(R.id.layoutWorldModelYesterday)
        tvYesterdayApp1           = findViewById(R.id.tvYesterdayApp1)
        tvYesterdayApp2           = findViewById(R.id.tvYesterdayApp2)
        tvYesterdayApp3           = findViewById(R.id.tvYesterdayApp3)
        // Service status indicator in header
        val viewServiceDot = findViewById<android.view.View?>(R.id.viewServiceDot)
        val tvServiceStatus = findViewById<android.widget.TextView?>(R.id.tvServiceStatus)
        // Dot is cyan when service is bound, grey when reconnecting
        viewServiceDot?.setBackgroundColor(if (serviceBound) 0xFF00E5FF.toInt() else 0xFF2E4257.toInt())

        btnOnDeviceDismiss?.setOnClickListener {
            clockHandler.removeCallbacks(clockRunnable)   // stop ticking
            cardOnDeviceMoment?.visibility = View.GONE
            getSharedPreferences(PREFS_MAIN, MODE_PRIVATE).edit()
                .putBoolean("on_device_moment_shown", true).apply()
        }

        // Main scroll view
        mainScrollView      = findViewById(R.id.mainScrollView)
        // Dream card
        cardDream           = findViewById(R.id.cardDream)
        layoutDreamActive   = findViewById(R.id.layoutDreamActive)
        layoutDreamComplete = findViewById(R.id.layoutDreamComplete)
        layoutDreamMissed   = findViewById(R.id.layoutDreamMissed)
        dreamAnimation      = findViewById(R.id.dreamAnimation)
        tvExamplePrimary    = findViewById(R.id.tvExamplePrimary)
        tvExampleSecondary1 = findViewById(R.id.tvExampleSecondary1)
        tvExampleSecondary2 = findViewById(R.id.tvExampleSecondary2)
        wireExampleCard()
        tvDreamCompleteTime = findViewById(R.id.tvDreamCompleteTime)
        tvDreamInteractions = findViewById(R.id.tvDreamInteractions)
        tvDreamPatterns     = findViewById(R.id.tvDreamPatterns)
        tvDreamRemoved      = findViewById(R.id.tvDreamRemoved)
        tvDreamHabits       = findViewById(R.id.tvDreamHabits)
        btnDreamDismiss     = findViewById(R.id.btnDreamDismiss)
        tvDreamMissedReason = findViewById(R.id.tvDreamMissedReason)
        tvDreamMissedDays   = findViewById(R.id.tvDreamMissedDays)

        btnDreamDismiss?.setOnClickListener { dismissDreamCard() }

        tvStatus.text = "⏳  Initialising Axiom..."

        // ⋮ button → overflow menu
        findViewById<TextView>(R.id.btnEventViewer).setOnClickListener {
            showOverflowMenu(it)
        }

        btnExecute.setOnClickListener {
            val text = inputField.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            runInference(text)
        }

        // Restore context card when user clears the input field
        inputField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (s.isNullOrEmpty()) {
                    cardLastResult.visibility = View.GONE
                    refreshContextCard()
                }
            }
        })

        // Hide proactive button (runs automatically via service)
        btnProactive.setOnClickListener { runProactiveSuggestion() }

        btnGrantPerms.setOnClickListener { requestRuntimePermissions() }

        btnDownloadSeed.setOnClickListener { startSeedDownload() }
        btnCancelDownload.setOnClickListener { cancelSeedDownload() }

        // Mic button - uses the device's built-in speech engine (same as keyboard mic)
        btnMic = findViewById(R.id.btnMic)
        btnMic.setOnClickListener { onMicButtonTapped() }

        // Wire suggestion chips - each chip's tag holds the query to pre-fill
        wireSuggestionChips()

        // Wire feature shortcut pills defined in activity_main.xml
        wireFeaturePills()
    }

    private fun wireSuggestionChips() {
        // Suggestion chips not present in this layout - no-op
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Feature 3 - Live Learning Bar
    //  Updates every onResume. Reads adapter stats off the main thread.
    //  Shows: "Model  -  71% confidence  -  23 interactions" + badge.
    // ════════════════════════════════════════════════════════════════════════
    private fun refreshLearningBar() {
        Thread {
            val stats = runCatching { AxiomEngine.getAdapterStats() }.getOrElse { "" }
            val interactions = parseStatField(stats, "interactions")
            // Use axiomService?.isReady() — same authoritative check as the rest of the app.
            // AxiomEngine.isEngineReady() is set asynchronously and can lag behind.
            val engineReady  = axiomService?.isReady() == true

            val statusText: String
            val badgeText:  String
            val dotColor:   Int

            when {
                !engineReady -> {
                    // Service not ready yet — show "initialising", not "loading"
                    // so it matches the ⏳ status bar text above
                    statusText = "On-device model  -  initialising"
                    badgeText  = "starting"
                    dotColor   = 0xFF2E4257.toInt()
                }
                interactions == 0 -> {
                    statusText = "On-device model  -  no cloud  -  ready"
                    badgeText  = "ready"
                    dotColor   = 0xFF00E5FF.toInt()
                }
                else -> {
                    val confPct   = interactions.coerceAtMost(100)
                    val predRows  = runCatching {
                        java.io.File(filesDir, "axiom_preds.csv")
                            .readLines().count { it.isNotBlank() && !it.startsWith("#") }
                    }.getOrElse { 0 }
                    val phase = when {
                        interactions < 5   -> "early learning"
                        interactions < 20  -> "improving"
                        interactions < 50  -> "pattern forming"
                        else               -> "deeply trained"
                    }
                    statusText = "Model  -  $confPct%  -  $interactions interactions  -  $predRows habits"
                    badgeText  = phase
                    dotColor   = 0xFF00E5FF.toInt()
                }
            }

            runOnUiThread {
                tvLearningStatus?.text = statusText
                tvLearningBadge?.text  = badgeText
                viewLearningDot?.setBackgroundColor(dotColor)
            }
        }.start()
    }

    private fun parseStatField(stats: String, key: String): Int =
        runCatching {
            stats.lines().firstOrNull { it.startsWith("${'$'}key=") }
                ?.substringAfter("=")?.trim()?.toInt() ?: 0
        }.getOrElse { 0 }

    /** Mirrors InsightEngine.isSystemPackage — keeps world model free of system apps. */
    private fun isSystemPkg(pkg: String): Boolean {
        val prefixes = listOf(
            "com.android.", "android.", "com.google.android.gms",
            "com.google.android.gsf", "com.google.android.inputmethod",
            "com.google.android.permissioncontroller", "com.google.android.packageinstaller",
            "com.miui.", "com.sec.android.", "com.huawei.", "com.oneplus.",
            "com.oppo.", "com.vivo.", "com.realme.", "com.nothing.launcher"
        )
        if (pkg == "android" || prefixes.any { pkg.startsWith(it) }) return true
        val keywords = listOf("launcher","systemui","inputmethod","keyboard",
            "wellbeing","screentime","setup","installer","permissioncontroller")
        return keywords.any { pkg.contains(it, ignoreCase = true) }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Feature 5 - World Model Card
    //  Collapsed summary by default. Tap expands to full breakdown.
    // ════════════════════════════════════════════════════════════════════════
    private fun refreshWorldModelCard() {
        Thread {
            val result = buildWorldModelText()
            runOnUiThread {
                tvWorldModelSummary?.text = result.summary
                tvWorldModelDetail?.text  = result.detail
                tvWorldModelFooter?.text  = result.footer
                tvWorldModelToggle?.visibility =
                    if (result.detail.isEmpty()) View.GONE else View.VISIBLE

                // Yesterday section: show only when no learning has happened yet
                if (result.yesterday.isNotEmpty()) {
                    tvYesterdayApp1?.text = result.yesterday.getOrElse(0) { "" }
                    tvYesterdayApp2?.text = result.yesterday.getOrElse(1) { "" }
                    tvYesterdayApp3?.text = result.yesterday.getOrElse(2) { "" }
                    layoutWorldModelYesterday?.visibility = View.VISIBLE
                } else {
                    layoutWorldModelYesterday?.visibility = View.GONE
                }
            }
        }.start()
    }

    private data class WorldModelText(
        val summary: String,
        val detail: String,
        val footer: String,
        val yesterday: List<String> = emptyList()   // top apps for cold-start display
    )

    private fun buildWorldModelText(): WorldModelText {
        val stats        = runCatching { AxiomEngine.getAdapterStats() }.getOrElse { "" }
        val interactions = parseStatField(stats, "interactions")

        val predFile = java.io.File(filesDir, "axiom_preds.csv")
        val predRows = runCatching {
            predFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        }.getOrElse { emptyList() }

        if (predRows.isEmpty() && interactions == 0) {
            // Day-1 cold start: pull yesterday's real top apps from UsageStatsManager.
            // No learning required — immediately personal from first open.
            val yesterday = getYesterdayTopApps()
            return WorldModelText(
                summary   = if (yesterday.isEmpty())
                    "Use Axiom for a few days — your world model will appear here."
                else
                    "Your world model is warming up — here's what I can already see:",
                yesterday = yesterday,
                detail    = "",
                footer    = ""
            )
        }

        data class PredRow(val intent: String, val hour: Int, val hits: Int, val total: Int)

        val parsed = predRows.mapNotNull { line ->
            val p = line.split(",")
            if (p.size < 4) null
            else runCatching {
                PredRow(p[0].trim(), p[1].trim().toInt(),
                    p[2].trim().toInt(), p[3].trim().toInt())
            }.getOrNull()
        }.filter { row ->
            row.total >= 3 &&
                    !(row.intent.startsWith("APP:") && isSystemPkg(row.intent.removePrefix("APP:")))
        }.sortedByDescending { it.hits.toFloat() / it.total }

        fun intentLabel(intent: String): String {
            if (!intent.startsWith("APP:")) {
                return intent.removePrefix("ACTION_").replace("_", " ").lowercase()
            }
            val pkg = intent.removePrefix("APP:")
            return runCatching {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(pkg, 0)
                ).toString()
            }.getOrElse { pkg.substringAfterLast('.') }
        }

        val topHabits = parsed.take(3).map { intentLabel(it.intent) }

        val summary = when {
            topHabits.isEmpty() ->
                "$interactions interactions  -  patterns emerging"
            topHabits.size == 1 ->
                "Knows: ${topHabits[0]} habit  -  $interactions interactions  -  ${predRows.size} patterns"
            else ->
                "Knows: ${topHabits.joinToString(", ")} + ${predRows.size} more patterns"
        }

        val detailLines = mutableListOf<String>()
        detailLines.add("* $interactions interactions recorded")
        detailLines.add("* ${predRows.size} hourly habit patterns")

        parsed.take(5).forEach { row ->
            val label = intentLabel(row.intent)
            val pct   = (row.hits.toFloat() / row.total * 100).toInt()
            val h12   = if (row.hour % 12 == 0) 12 else row.hour % 12
            val amPm  = if (row.hour < 12) "AM" else "PM"
            detailLines.add("* $label at $h12 $amPm  -  $pct% of the time")
        }

        return WorldModelText(
            summary = summary,
            detail  = detailLines.joinToString("\n"),
            footer  = "All patterns learned on-device  -  0 bytes sent anywhere"
        )
    }

    /**
     * Queries UsageStatsManager for yesterday's top 3 user-facing apps by screen time.
     * Returns formatted strings ready for display. Works on day 1 — no learning needed.
     * Returns empty list if permission not granted or no data available.
     */
    private fun getYesterdayTopApps(): List<String> {
        if (!UsageAnalyser.hasPermission(this)) return emptyList()
        return runCatching {
            val usm = getSystemService(USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val cal = java.util.Calendar.getInstance()
            // End = start of today (midnight)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val endMs   = cal.timeInMillis
            val startMs = endMs - 24L * 60 * 60 * 1000   // exactly 24h prior = yesterday

            val stats = usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                startMs, endMs
            ) ?: return emptyList()

            data class AppUsage(val label: String, val mins: Long)

            stats
                .filter { it.totalTimeInForeground > 60_000L }   // at least 1 min
                .filter { !isSystemPkg(it.packageName) }
                .mapNotNull { stat ->
                    val label = runCatching {
                        packageManager.getApplicationLabel(
                            packageManager.getApplicationInfo(stat.packageName, 0)
                        ).toString()
                    }.getOrNull() ?: return@mapNotNull null
                    AppUsage(label, stat.totalTimeInForeground / 60_000L)
                }
                .sortedByDescending { it.mins }
                .take(3)
                .mapIndexed { i, app ->
                    val hrs  = app.mins / 60
                    val mins = app.mins % 60
                    val time = if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
                    val rank = listOf("1st", "2nd", "3rd").getOrElse(i) { "${i+1}th" }
                    "$rank   ${app.label.padEnd(20)}  $time yesterday"
                }
        }.getOrElse { emptyList() }
    }

    private fun toggleWorldModel() {
        worldModelExpanded = !worldModelExpanded
        layoutWorldModelDetail?.visibility = if (worldModelExpanded) View.VISIBLE else View.GONE
        tvWorldModelToggle?.text = if (worldModelExpanded) "▴  collapse" else "▾  expand"
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Feature 4 - On-Device Moment
    //  Called once after the very first successful inference.
    //  Shows a one-time card: "That just ran on your device."
    //  inferenceMs = time the inference took, for display in the stats row.
    // ════════════════════════════════════════════════════════════════════════
    private fun maybeShowOnDeviceMoment(inferenceMs: Long) {
        val prefs = getSharedPreferences(PREFS_MAIN, MODE_PRIVATE)
        if (prefs.getBoolean("on_device_moment_shown", false)) return
        // Mark immediately so it never shows again even if dismissed via back
        prefs.edit().putBoolean("on_device_moment_shown", true).apply()

        // Fill inference ms stat
        tvOnDeviceMs?.text = "${inferenceMs}ms"

        // Start the live clock — updates every second so the user sees their
        // device working in real time. Stopped when card is dismissed.
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        tvOnDeviceClock?.text = fmt.format(java.util.Date())
        clockHandler.removeCallbacks(clockRunnable)  // safety: never double-post
        clockHandler.post(clockRunnable)

        cardOnDeviceMoment?.visibility = View.VISIBLE
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Dream card — 3 states: dreaming / complete / missed
    // ════════════════════════════════════════════════════════════════════════

    private fun registerDreamReceiver() {
        if (dreamReceiver != null) return
        dreamReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: Intent) {
                when (intent.action) {
                    DreamService.ACTION_DREAM_STARTED  -> showDreamingState()
                    DreamService.ACTION_DREAM_COMPLETE -> {
                        val json = intent.getStringExtra(DreamService.EXTRA_DREAM_RESULT) ?: ""
                        // Hold the animation for at least 4s. When the dream is tiny
                        // (< 5 events) it finishes in < 100ms — both broadcasts arrive
                        // in the same Handler tick and the animation is never visible.
                        val MIN_ANIM_MS = 4_000L
                        val elapsed = System.currentTimeMillis() - dreamAnimStartMs
                        val delay   = (MIN_ANIM_MS - elapsed).coerceAtLeast(0L)
                        uiHandler.postDelayed({ showDreamCompleteState(json) }, delay)
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(DreamService.ACTION_DREAM_STARTED)
            addAction(DreamService.ACTION_DREAM_COMPLETE)
        }
        // RECEIVER_NOT_EXPORTED required on API 33+ for internal-only broadcasts
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dreamReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(dreamReceiver, filter)
        }
    }

    private fun unregisterDreamReceiver() {
        dreamReceiver?.let { runCatching { unregisterReceiver(it) } }
        dreamReceiver = null
    }

    /**
     * Called on every onResume — reads persisted dream state so the card
     * shows correctly even if dreaming completed while the app was closed.
     */
    private fun updateDreamCard() {
        val prefs = getSharedPreferences(DreamService.PREFS_DREAM_UI, MODE_PRIVATE)
        val state = prefs.getString(DreamService.KEY_DREAM_STATE, "idle") ?: "idle"

        when (state) {
            "dreaming" -> showDreamingState()
            "complete" -> {
                val json = prefs.getString(DreamService.KEY_DREAM_RESULT_JSON, "") ?: ""
                showDreamCompleteState(json, fromResume = true)
            }
            else -> checkDreamMissed()
        }
    }

    private fun showDreamingState() {
        dreamAnimStartMs                = System.currentTimeMillis()
        cardDream?.visibility           = View.VISIBLE
        layoutDreamActive?.visibility   = View.VISIBLE
        layoutDreamComplete?.visibility = View.GONE
        layoutDreamMissed?.visibility   = View.GONE
        dreamAnimation?.start()
        scrollToDreamCard()
    }

    private fun showDreamCompleteState(resultJson: String, fromResume: Boolean = false) {
        dreamAnimation?.stop()
        cardDream?.visibility           = View.VISIBLE
        layoutDreamActive?.visibility   = View.GONE
        layoutDreamComplete?.visibility = View.VISIBLE
        layoutDreamMissed?.visibility   = View.GONE
        scrollToDreamCard()
        // Only toast when the user opens the app AFTER a dream completed.
        // If they watched it live the animation already told the story.
        if (fromResume) {
            android.widget.Toast.makeText(
                this, "✨  Axiom dreamed — see results below", android.widget.Toast.LENGTH_LONG
            ).show()
        }

        // Parse the dream_summary.json written by DreamService
        runCatching {
            val root         = org.json.JSONObject(resultJson)
            val dreamLearning = runCatching {
                org.json.JSONObject(root.optString("dream_learning", "{}"))
            }.getOrNull()
            val adapterObj   = runCatching {
                org.json.JSONObject(root.optString("adapter", "{}"))
            }.getOrNull()

            val events    = root.optInt("events_before", 0)
            val trained   = dreamLearning?.optInt("trained", 0) ?: 0
            val removed   = root.optInt("removed", 0)
            val topIntents = adapterObj?.optString("top_intents", "") ?: ""
            val dreamTs   = root.optLong("dream_ts", 0L)

            tvDreamInteractions?.text = events.toString()
            tvDreamPatterns?.text     = trained.toString()
            tvDreamRemoved?.text      = removed.toString()

            // Format dream time
            if (dreamTs > 0L) {
                val sdf = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                tvDreamCompleteTime?.text = sdf.format(java.util.Date(dreamTs))
            }

            // Top habits text
            if (topIntents.isNotEmpty()) {
                val habits = topIntents.split(",").take(3)
                    .joinToString(" - ") { it.trim().removePrefix("ACTION_").replace("_"," ").lowercase().replaceFirstChar { c -> c.uppercase() } }
                tvDreamHabits?.text = "Most reinforced habits: $habits"
                tvDreamHabits?.visibility = View.VISIBLE
            } else {
                tvDreamHabits?.visibility = View.GONE
            }
        }
    }

    /** Scrolls the main view to bring the dream card into sight. */
    private fun scrollToDreamCard() {
        val card = cardDream ?: return
        val sv   = mainScrollView ?: return
        sv.postDelayed({
            sv.smoothScrollTo(0, (card.top - 24).coerceAtLeast(0))
        }, 250L)
    }

    private fun checkDreamMissed() {
        val prefs       = getSharedPreferences(ChargingReceiver.PREFS_NAME, MODE_PRIVATE)
        val lastDreamTs = prefs.getLong(ChargingReceiver.KEY_LAST_DREAM_TS, 0L) * 1000L
        val nowMs       = System.currentTimeMillis()
        val hoursSince  = (nowMs - lastDreamTs) / 3600_000L

        // Only show "missed" if it's been more than 36h AND last dream was at least once
        if (lastDreamTs == 0L || hoursSince < 36) {
            cardDream?.visibility = View.GONE
            return
        }

        cardDream?.visibility           = View.VISIBLE
        layoutDreamActive?.visibility   = View.GONE
        layoutDreamComplete?.visibility = View.GONE
        layoutDreamMissed?.visibility   = View.VISIBLE

        val daysSince = (hoursSince / 24).toInt().coerceAtLeast(1)
        tvDreamMissedDays?.text = "${daysSince}d"

        val reason = when {
            hoursSince < 48 -> "Last learning was yesterday. Plug in with 50%+ battery and Axiom will dream."
            else            -> "No learning for $daysSince days. Plug in with 50%+ battery and Axiom will dream."
        }
        tvDreamMissedReason?.text = reason
    }

    private fun dismissDreamCard() {
        dreamAnimation?.stop()
        cardDream?.visibility = View.GONE
        // Clear the complete state so it doesn't re-show on next resume
        getSharedPreferences(DreamService.PREFS_DREAM_UI, MODE_PRIVATE).edit()
            .putString(DreamService.KEY_DREAM_STATE, "idle").apply()
    }

    private fun wireFeaturePills() {
        fun wire(idName: String, action: () -> Unit) {
            val id = resources.getIdentifier(idName, "id", packageName)
            if (id != 0) findViewById<android.widget.TextView>(id)
                ?.setOnClickListener { action() }
        }
        wire("pillProfile")      { startActivity(Intent(this, AxiomProfileActivity::class.java)) }
        wire("pillIntelligence") { startActivity(Intent(this, InsightActivity::class.java)) }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Rotating example card — "Try saying…"
    //  Each set has 3 examples (primary + 2 secondary).
    //  Rotates to the next set every time onResume fires.
    // ═══════════════════════════════════════════════════════════════════════

    private data class ExampleSet(val primary: String, val s1: String, val s2: String)

    private val exampleSets = listOf(
        ExampleSet("Open WhatsApp",                   "Navigate to work",          "Play lofi on Spotify"),
        ExampleSet("Call mom on WhatsApp",             "Book an Ola to airport",    "Set alarm for 7 AM"),
        ExampleSet("Show me videos from yesterday",   "Search photos last week",   "Turn on WiFi"),
        ExampleSet("Play Shape of You on Spotify",    "Search dark knight on YouTube", "Order food on Zomato"),
        ExampleSet("Video call sister on WhatsApp",   "Message John on Telegram",  "What time is it in London"),
        ExampleSet("Search directions to hospital",   "Remind me at 6 PM",         "Turn on Do Not Disturb"),
        ExampleSet("Find files named report",         "Post good morning on Instagram", "Enable dark mode"),
        ExampleSet("Watch Stranger Things on Netflix","Search headphones on Amazon","Book a cab to station"),
        ExampleSet("Play podcast on Spotify",         "Tweet good morning",        "Check battery health"),
        ExampleSet("Open Google Maps",                "Scan AI-generated text",    "Search flights to Delhi"),
    )

    private fun wireExampleCard() {
        // Tapping any example pre-fills the input and focuses it
        fun tapFill(view: android.widget.TextView?) {
            view?.setOnClickListener {
                inputField.setText(view.text)
                inputField.requestFocus()
                inputField.setSelection(view.text.length)
            }
        }
        tapFill(tvExamplePrimary)
        tapFill(tvExampleSecondary1)
        tapFill(tvExampleSecondary2)

        // Also wire the parent containers (the bg_input rows)
        fun tapFillParent(childView: android.widget.TextView?) {
            (childView?.parent as? android.view.View)?.setOnClickListener {
                inputField.setText(childView.text)
                inputField.requestFocus()
                inputField.setSelection(childView.text.length)
            }
        }
        tapFillParent(tvExamplePrimary)
        tapFillParent(tvExampleSecondary1)
        tapFillParent(tvExampleSecondary2)
    }

    private fun updateExampleCard() {
        if (tvExamplePrimary == null) return
        exampleSetIndex = (exampleSetIndex + 1) % exampleSets.size
        val set = exampleSets[exampleSetIndex]
        tvExamplePrimary?.text    = set.primary
        tvExampleSecondary1?.text = set.s1
        tvExampleSecondary2?.text = set.s2
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "🧠  What Axiom Knows")
        popup.menu.add(0, 2, 0, "📊  Analyse Usage")
        popup.menu.add(0, 3, 0, "🌙  Dream Summary")
        popup.menu.add(0, 5, 0, "🔬  Axiom Intelligence")
        popup.menu.add(0, 7, 0, "⚕  System Health")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> startActivity(Intent(this, AxiomProfileActivity::class.java))
                2 -> runUsageAnalysis()
                3 -> showDreamSummary()
                5 -> startActivity(Intent(this, InsightActivity::class.java))
                7 -> startActivity(Intent(this, HealthCheckActivity::class.java))
            }
            true
        }
        popup.show()
    }

    private fun openFileSearch() {
        if (FileSearchEngine.hasPermission(this)) {
            startActivity(Intent(this, FileSearchActivity::class.java))
        } else {
            AlertDialog.Builder(this)
                .setTitle("File Search")
                .setMessage(
                    "Axiom needs access to your media files to search them.\n\n" +
                            "Everything happens on-device - no data leaves your phone."
                )
                .setPositiveButton("Grant Access") { _, _ ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaPermLauncher.launch(arrayOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO,
                            Manifest.permission.READ_MEDIA_AUDIO
                        ))
                    } else {
                        mediaPermLauncher.launch(arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        ))
                    }
                }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Permission management - Google Play compliant
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Called on first launch and whenever the permission card's "Grant" is tapped.
     * Requests in the right order: notifications first (most visible), then BT.
     * Usage Access cannot be requested via dialog - we open Settings instead.
     */
    private fun requestRuntimePermissions() {
        // 1. POST_NOTIFICATIONS (Android 13+ / API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasNotificationPermission()) {
                // Show rationale before requesting
                AlertDialog.Builder(this)
                    .setTitle("Smart Suggestions")
                    .setMessage(
                        "Axiom sends proactive suggestions based on your habits - " +
                                "like opening Spotify when you usually listen to music.\n\n" +
                                "Allow notifications to receive these suggestions."
                    )
                    .setPositiveButton("Allow") { _, _ ->
                        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    .setNegativeButton("Not now") { _, _ -> updatePermissionCard() }
                    .show()
                return
            }
        }

        // 2. BLUETOOTH_CONNECT (Android 12+ / API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasBluetoothPermission()) {
                AlertDialog.Builder(this)
                    .setTitle("Context Awareness")
                    .setMessage(
                        "Axiom uses Bluetooth state as one of several signals to " +
                                "make smarter suggestions (e.g. headphone-related settings " +
                                "when Bluetooth is on). No device data is read."
                    )
                    .setPositiveButton("Allow") { _, _ ->
                        bluetoothPermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                    .setNegativeButton("Skip") { _, _ -> updatePermissionCard() }
                    .show()
                return
            }
        }

        // 3. PACKAGE_USAGE_STATS - special permission, must open Settings
        if (!UsageAnalyser.hasPermission(this)) {
            AlertDialog.Builder(this)
                .setTitle("How Axiom learns your habits")
                .setMessage(
                    "Axiom needs to see which apps you open and when — " +
                            "so it can learn patterns like \"opens Maps every morning\" " +
                            "or \"listens to Spotify on Tuesday evenings\".\n\n" +
                            "This data never leaves your phone. It's used only to build " +
                            "your personal model. Without it, Axiom can't learn or make " +
                            "suggestions — it'll just be a voice launcher.\n\n" +
                            "In the next screen: find Axiom in the list and tap " +
                            "\"Permit usage access\"."
                )
                .setPositiveButton("Let Axiom learn") { _, _ ->
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                .setNegativeButton("Stay as voice launcher") { _, _ -> updatePermissionCard() }
                .show()
            return
        }

        // 4. READ_CALENDAR - for LifeContextAssistant proactive reminders
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this)
                .setTitle("Smart Calendar Reminders")
                .setMessage(
                    "Axiom can read your calendar to remind you about upcoming flights, " +
                            "hotel check-ins, and meetings - and suggest actions like " +
                            "booking a cab before a flight.\n\n" +
                            "No calendar data ever leaves your device."
                )
                .setPositiveButton("Allow") { _, _ ->
                    calendarPermLauncher.launch(Manifest.permission.READ_CALENDAR)
                }
                .setNegativeButton("Not now") { _, _ -> updatePermissionCard() }
                .show()
            return
        }

        updatePermissionCard()
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun allPermissionsGranted(): Boolean {
        return hasNotificationPermission()
                && hasBluetoothPermission()
                && UsageAnalyser.hasPermission(this)
    }

    /**
     * Shows the permission card with the appropriate hint if any permission
     * is missing. Hides it entirely once all are granted.
     */
    private fun updatePermissionCard() {
        val notif = hasNotificationPermission()
        val bt    = hasBluetoothPermission()
        val usage = UsageAnalyser.hasPermission(this)

        if (notif && bt && usage) {
            cardPermissions.visibility = View.GONE
            maybeRunInitialUsageScan()
            return
        }

        cardPermissions.visibility = View.VISIBLE

        val missing = buildList {
            if (!notif)  add("• Notifications - for proactive suggestions")
            if (!bt)     add("• Bluetooth - for context awareness")
            if (!usage)  add("• Usage Access - to learn from your habits")
        }
        tvPermissionHint.text = missing.joinToString("\n")
    }

    /**
     * Shows the seed card only when the model file is absent.
     * Hides it once the file exists in filesDir.
     */
    private fun updateSeedCard() {
        val seedFile = File(filesDir, SEED_FILENAME)
        if (seedFile.exists() && seedFile.length() > 100_000_000L) {
            cardSeedDownload.visibility = View.GONE
        } else {
            cardSeedDownload.visibility = View.VISIBLE
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Seed model download via DownloadManager
    //  No WRITE_EXTERNAL_STORAGE needed - we download to getExternalFilesDir
    //  (app-scoped, no permission required on API 19+), then move to filesDir.
    // ════════════════════════════════════════════════════════════════════════
    private fun startSeedDownload() {
        if (SEED_DOWNLOAD_URL == "https://YOUR_SERVER/axiom_seed_q4.gguf") {
            AlertDialog.Builder(this)
                .setTitle("Download URL Not Set")
                .setMessage("The seed download URL has not been configured yet. " +
                        "Please add your hosted model URL to MainActivity.SEED_DOWNLOAD_URL.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        // Don't start a second download if one is running
        if (downloadId != -1L) return

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // ── Resume check: if a previous download ID is saved, reconnect to it ──
        // This handles phone-lock / activity-recreate without restarting from zero.
        val savedId = getSharedPreferences(PREFS_DOWNLOAD, MODE_PRIVATE)
            .getLong(PREF_DOWNLOAD_ID, -1L)
        if (savedId != -1L) {
            val q = DownloadManager.Query().setFilterById(savedId)
            dm.query(q)?.use { c ->
                if (c.moveToFirst()) {
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_RUNNING ||
                        status == DownloadManager.STATUS_PENDING ||
                        status == DownloadManager.STATUS_PAUSED) {
                        // Reconnect to the existing in-progress download
                        downloadId = savedId
                        btnDownloadSeed.isEnabled = false
                        btnCancelDownload.visibility = View.VISIBLE
                        downloadProgress.visibility = View.VISIBLE
                        tvDownloadStatus.visibility = View.VISIBLE
                        tvDownloadStatus.text = "Resuming download..."
                        uiHandler.post(downloadPollRunnable)
                        android.util.Log.i("AxiomDownload", "Reconnected to existing download $savedId")
                        return
                    }
                }
            }
            // Saved ID is stale (failed/cancelled) - clear it
            getSharedPreferences(PREFS_DOWNLOAD, MODE_PRIVATE).edit()
                .remove(PREF_DOWNLOAD_ID).apply()
        }

        // ── Fresh download ─────────────────────────────────────────────────────
        btnDownloadSeed.isEnabled = false
        btnCancelDownload.visibility = View.VISIBLE
        downloadProgress.visibility = View.VISIBLE
        tvDownloadStatus.visibility = View.VISIBLE
        tvDownloadStatus.text = "Starting download..."

        val destDir  = getExternalFilesDir(null) ?: filesDir
        val destFile = File(destDir, SEED_FILENAME)
        // Only delete if file is fully present but broken - partial files are kept
        // by DownloadManager itself so we never need to wipe them manually here.

        val request = DownloadManager.Request(Uri.parse(SEED_DOWNLOAD_URL)).apply {
            setTitle("Axiom – Downloading AI model")
            setDescription("Qwen2.5-1.5B Q4  (~900 MB)  -  continues in background")
            // Show in notification bar while downloading AND when complete
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(destFile))
            setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or
                        DownloadManager.Request.NETWORK_MOBILE)
            setAllowedOverRoaming(false)
        }

        downloadId = dm.enqueue(request)

        // Persist the ID so we can reconnect after a lock-screen / process death
        getSharedPreferences(PREFS_DOWNLOAD, MODE_PRIVATE).edit()
            .putLong(PREF_DOWNLOAD_ID, downloadId).apply()

        android.util.Log.i("AxiomDownload", "Enqueued new download id=$downloadId")
        uiHandler.post(downloadPollRunnable)
    }

    private fun pollDownloadProgress() {
        val id = downloadId
        if (id == -1L) return

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(id)
        val cursor: Cursor? = dm.query(query)

        cursor?.use { c ->
            if (c.moveToFirst()) {
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val downloaded = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

                when (status) {
                    DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                        if (total > 0) {
                            val pct = (downloaded * 100 / total).toInt()
                            downloadProgress.progress = pct
                            val dlMb = downloaded / 1_048_576
                            val totMb = total / 1_048_576
                            tvDownloadStatus.text = "$dlMb MB / $totMb MB  ($pct%)"
                        } else {
                            tvDownloadStatus.text = "Connecting..."
                        }
                        // Poll again in 1 second
                        uiHandler.postDelayed(downloadPollRunnable, 1000)
                    }

                    DownloadManager.STATUS_SUCCESSFUL -> {
                        onDownloadComplete()
                    }

                    DownloadManager.STATUS_FAILED -> {
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        onDownloadFailed("Error code $reason")
                    }
                }
            }
        }
    }

    private fun onDownloadComplete() {
        downloadId = -1L
        uiHandler.removeCallbacks(downloadPollRunnable)
        // Clear persisted download ID - download is done
        getSharedPreferences(PREFS_DOWNLOAD, MODE_PRIVATE).edit()
            .remove(PREF_DOWNLOAD_ID).apply()

        // Move file from external app dir into private filesDir
        val srcFile = File(getExternalFilesDir(null), SEED_FILENAME)
        val dstFile = File(filesDir, SEED_FILENAME)

        Thread {
            try {
                if (srcFile.exists()) {
                    srcFile.copyTo(dstFile, overwrite = true)
                    srcFile.delete()
                }
                runOnUiThread {
                    tvDownloadStatus.text = "✅  Seed ready"
                    downloadProgress.progress = 100
                    btnCancelDownload.visibility = View.GONE
                    btnDownloadSeed.isEnabled = true
                    btnDownloadSeed.text = "✅  Seed Downloaded"
                    btnDownloadSeed.isEnabled = false
                    // Tell service to initialise with the new file
                    val svcIntent = Intent(this, AxiomService::class.java)
                    startService(svcIntent)
                    updateSeedCard()
                    Toast.makeText(this, "Model ready - Axiom is initialising", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { onDownloadFailed(e.message ?: "Copy failed") }
            }
        }.start()
    }

    private fun onDownloadFailed(reason: String) {
        downloadId = -1L
        uiHandler.removeCallbacks(downloadPollRunnable)
        // Clear persisted ID - will start fresh next time
        getSharedPreferences(PREFS_DOWNLOAD, MODE_PRIVATE).edit()
            .remove(PREF_DOWNLOAD_ID).apply()
        downloadProgress.visibility = View.GONE
        tvDownloadStatus.text = "❌  Download failed: $reason"
        btnCancelDownload.visibility = View.GONE
        btnDownloadSeed.isEnabled = true
    }

    private fun cancelSeedDownload() {
        if (downloadId != -1L) {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(downloadId)
            downloadId = -1L
        }
        // Clear persisted ID - user cancelled, start fresh next time
        getSharedPreferences(PREFS_DOWNLOAD, MODE_PRIVATE).edit()
            .remove(PREF_DOWNLOAD_ID).apply()
        uiHandler.removeCallbacks(downloadPollRunnable)
        downloadProgress.visibility = View.GONE
        tvDownloadStatus.visibility = View.GONE
        btnCancelDownload.visibility = View.GONE
        btnDownloadSeed.isEnabled = true
        tvDownloadStatus.text = ""
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Service ready callback
    // ════════════════════════════════════════════════════════════════════════
    private fun onServiceReady() {
        if (axiomService?.isReady() == true) {
            tvStatus.text = "✅  Axiom Ready"
            // Engine is now confirmed ready — refresh learning bar immediately
            refreshLearningBar()
            refreshWorldModelCard()
        } else {
            tvStatus.text = "⏳  Axiom initialising..."
            uiHandler.removeCallbacks(readyPoller)
            uiHandler.postDelayed(readyPoller, 500)
        }
        updateSeedCard()
        updatePermissionCard()
        maybeRunInitialUsageScan()
        // Auto-surface last dream session if it ran in the last 24 hours
        // and hasn't been dismissed today. Non-intrusive - shows as snackbar.
        uiHandler.postDelayed({ maybeSurfaceDreamBanner() }, 1500)
        // Pre-warm launcher cache on a background thread immediately.
        // This way the first inference never pays the 2-5s cold-scan cost.
        Thread {
            runCatching { getLauncherCache() }
            android.util.Log.d("AxiomMain", "Launcher cache warmed: ${launcherCache?.size} entries")
        }.start()
    }

    private fun maybeSurfaceDreamBanner() {
        val svc = axiomService ?: return
        val prefs = getSharedPreferences("axiom_dream", MODE_PRIVATE)
        val dreamTs      = prefs.getLong("last_dream_ts", 0L)
        val dismissedDay = prefs.getLong("dream_banner_dismissed_day", 0L)
        val todayDay     = System.currentTimeMillis() / (24 * 60 * 60_000L)

        // Only show if dream ran in last 24h and not dismissed today
        val dreamAgeMs = System.currentTimeMillis() - dreamTs
        if (dreamTs == 0L || dreamAgeMs > 24 * 60 * 60_000L || dismissedDay == todayDay) return

        val summary = svc.getLastDreamSummary() ?: return
        val firstLine = summary.lines().firstOrNull() ?: return

        // Find the root view for Snackbar
        val rootView = findViewById<android.view.View>(android.R.id.content)
        val snack = com.google.android.material.snackbar.Snackbar.make(
            rootView, firstLine, com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
        snack.setAction("Details") {
            // Mark dismissed for today so it doesn't re-appear
            prefs.edit().putLong("dream_banner_dismissed_day", todayDay).apply()
            showDreamSummaryFull(summary)
        }
        snack.addCallback(object : com.google.android.material.snackbar.Snackbar.Callback() {
            override fun onDismissed(sb: com.google.android.material.snackbar.Snackbar?, event: Int) {
                if (event != DISMISS_EVENT_ACTION) {
                    prefs.edit().putLong("dream_banner_dismissed_day", todayDay).apply()
                }
            }
        })
        snack.show()
        android.util.Log.d("AxiomMain", "Dream banner shown: ${firstLine.take(50)}")
    }

    private fun showDreamSummaryFull(summary: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("🌙 Last Dream Session")
            .setMessage(summary)
            .setPositiveButton("Got it") { _, _ -> }
            .show()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Inference
    // ════════════════════════════════════════════════════════════════════════
    private fun runInference(prompt: String) {
        val svc = axiomService
        if (svc == null || !svc.isReady()) {
            tvStatus.text = "⏳  Engine initialising - try again shortly"
            return
        }

        // Guard 1 - time debounce (5s).
        // MIUI SpeechRecognizer fires onResults twice for the same utterance.
        // The second result may be worded slightly differently ("battery low" vs
        // "battery is low") so we debounce on TIME, not text content.
        val now = System.currentTimeMillis()
        if (now - lastInferenceTs < 5_000L) {
            android.util.Log.d("AxiomMain",
                "Debounced (${now - lastInferenceTs}ms since last): '${prompt.take(30)}'")
            return
        }
        lastInferenceTs = now

        // Capture voice flag before any async work, then reset for next call
        val isVoiceInput = voiceTriggered
        voiceTriggered = false

        // Guard 2 - in-flight lock.
        // If a previous svc.infer() is still running, drop this call entirely.
        // Prevents two LLM threads running simultaneously.
        if (inferInFlight) {
            android.util.Log.d("AxiomMain", "Dropped - infer already in flight")
            return
        }

        // Guard 3 - generation counter for stale result disposal.
        val myGeneration = ++inferGeneration

        // ── Everything runs on background thread — no PackageManager on main thread ─
        // resolveAppLaunch calls pm.getInstalledPackages (200-300 pkgs on MIUI = 2-5s).
        // Calling it on the main thread was the ANR root cause. Now fully off-thread.
        lastUserPrompt = prompt
        setProcessingState(true)
        tvStatus.text = "🧠  Thinking..."
        inferInFlight = true
        val inferStartMs = System.currentTimeMillis()
        Thread {
            // Stage A: try instant app-launch resolve first (uses cached package list)
            val appResult = runCatching { resolveAppLaunch(prompt) }.getOrNull()
            if (appResult != null) {
                inferInFlight = false
                runOnUiThread {
                    if (inferGeneration != myGeneration) {
                        setProcessingState(false); return@runOnUiThread
                    }
                    handleResult(appResult, isVoiceInput = isVoiceInput)
                }
                return@Thread
            }
            // Stage B: full LLM/adapter inference
            svc.collectSensors()
            val raw  = svc.infer(prompt)
            val json = runCatching { JSONObject(raw) }.getOrNull()
            inferInFlight = false
            runOnUiThread {
                if (inferGeneration != myGeneration) {
                    android.util.Log.d("AxiomMain",
                        "Dropped stale result gen=$myGeneration prompt='${prompt.take(25)}'")
                    setProcessingState(false)
                    return@runOnUiThread
                }
                handleResult(json, isVoiceInput = isVoiceInput)
                if (json != null) maybeShowOnDeviceMoment(System.currentTimeMillis() - inferStartMs)
            }
        }.start()
    }

    /**
     * Checks if [input] is asking to open an app. Returns a JSONObject ready
     * for handleResult() if matched, null otherwise.
     *
     * Two-stage lookup:
     *   1. Known apps map (name → list of package candidates, first installed wins)
     *   2. Scan all installed apps by visible label (handles any unlisted app)
     */
    // Cached launcher app list - built once per session.
    // Maps package name → ResolveInfo (contains exact activity class name).
    // We need the class name because on MIUI, only explicit component intents work.
    private var launcherCache: Map<String, android.content.pm.ResolveInfo>? = null

    @Synchronized
    private fun getLauncherCache(): Map<String, android.content.pm.ResolveInfo> {
        launcherCache?.let { return it }
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = runCatching { packageManager.queryIntentActivities(intent, 0) }.getOrNull() ?: emptyList()
        val map  = list.associateBy { it.activityInfo.packageName }
        launcherCache = map
        android.util.Log.d("AxiomMain", "launcher cache: ${map.size} apps (ROM may cap this)")
        return map
    }

    // Build a launch intent that works on ALL ROMs including MIUI.
    // Strategy:
    //   1. Use explicit ComponentName from launcher cache if available (best, works everywhere)
    //   2. Fall back to getLaunchIntentForPackage (works on stock Android)
    //   3. If both return null, the app is not installed / not launchable
    private fun explicitLaunchIntent(pkg: String): android.content.Intent? {
        // Try launcher cache first (explicit component - bypasses all ROM restrictions)
        val ri = getLauncherCache()[pkg]
        if (ri != null) {
            return android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                component = android.content.ComponentName(pkg, ri.activityInfo.name)
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
        }
        // Launcher cache only has 18 apps on MIUI - fall back to getLaunchIntentForPackage.
        // This works for apps we already know the package name of (from our known map).
        return packageManager.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
    }

    // Check if a package is installed (without needing to launch it)
    private fun isPackageInstalled(pkg: String): Boolean =
        runCatching { packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    private fun resolveAppLaunch(input: String): JSONObject? {
        val s = input.trim().lowercase()
            .removePrefix("open ").removePrefix("launch ")
            .removePrefix("start ").removePrefix("run ")
            .removePrefix("show ").removePrefix("go to ")
            .trim()
        if (s.length < 2) return null

        // ── Fast bail-out: never scan 269 packages for conversational input ─────────
        // App names: short (≤3 words), no punctuation, ≤30 chars.
        // "hello kaise ho?" / "who are you?" must return null immediately.
        // Without this, the package scan crashes on corrupt PackageInfo entries.
        val wordCount = s.split(Regex("\\s+")).size
        val hasQuestionMark = s.contains("?")
        val hasSentenceMarkers = s.contains("!") || s.contains(",") || s.contains(".")
        val conversationalWords = listOf("hello","hi","hey","kaise","kya","kaisa",
            "who","what","how","why","when","where","which","can","could","would","should",
            "tell","show me","remind","set","turn","call","dial","send","play alarm",
            "please","thanks","thank","aap","mera","mujhe","mein","hai","hain","karo")
        val hasConvoWord = conversationalWords.any { s.contains(it) }
        val tooLong = s.length > 25
        val tooManyWords = wordCount > 3
        if (hasQuestionMark || hasSentenceMarkers || hasConvoWord || tooLong || tooManyWords)
            return null

        val pm    = packageManager
        val cache = getLauncherCache()
        val allPackages = runCatching { pm.getInstalledPackages(0) }.getOrNull() ?: emptyList()
        android.util.Log.d("AxiomMain", "resolveAppLaunch: s=\'$s\' total=${allPackages.size} launcher=${cache.size}")

        fun makeResult(pkg: String, label: String) = JSONObject().apply {
            put("object",         "APP_LAUNCH")
            put("confidence",     1.0)
            put("android_intent", "app:$pkg")
            put("app_label",      label)
        }

        // Stage 1: match by visible app label across ALL installed packages
        // Use isPackageInstalled check - NOT explicitLaunchIntent - to avoid
        // filtering out apps that aren't in the 18-app MIUI launcher cache.
        // We build the actual launch intent later in handleResult.
        var exactMatch: Pair<String,String>? = null
        var partialMatch: Pair<String,String>? = null
        for (pkgInfo in allPackages) {
            val pkg     = pkgInfo.packageName
            val appInfo = pkgInfo.applicationInfo ?: continue
            // Skip background/system packages that have no launcher icon
            val isLaunchable = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0) ||
                    getLauncherCache().containsKey(pkg)
            if (!isLaunchable && !getLauncherCache().containsKey(pkg)) {
                // For user-installed apps, always include them
                if (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) continue
            }
            val label = runCatching { pm.getApplicationLabel(appInfo).toString().lowercase() }
                .getOrNull() ?: continue
            // Skip very short labels that would cause false matches
            if (label.length < 3 && label != s) continue
            when {
                label == s                                -> { exactMatch = pkg to label; break }
                label.contains(s) && partialMatch == null -> partialMatch = pkg to label
            }
        }
        val labelMatch = exactMatch ?: partialMatch
        if (labelMatch != null) {
            android.util.Log.i("AxiomMain", "App label match: \'$s\' -> ${labelMatch.first}")
            return makeResult(labelMatch.first, labelMatch.second)
        }

        // Stage 2: known package map - handles locale where label != English name
        val known = mapOf(
            "spotify" to "com.spotify.music", "youtube" to "com.google.android.youtube",
            "netflix" to "com.netflix.mediaclient", "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android", "facebook" to "com.facebook.katana",
            "telegram" to "org.telegram.messenger", "snapchat" to "com.snapchat.android",
            "twitter" to "com.twitter.android", "tiktok" to "com.zhiliaoapp.musically",
            "gmail" to "com.google.android.gm", "maps" to "com.google.android.apps.maps",
            "chrome" to "com.android.chrome", "zoom" to "us.zoom.videomeetings",
            "discord" to "com.discord", "amazon" to "com.amazon.mShop.android.shopping",
            "flipkart" to "com.flipkart.android", "paytm" to "net.one97.paytm",
            "phonepe" to "com.phonepe.app", "playstore" to "com.android.vending",
            "play store" to "com.android.vending", "settings" to "com.android.settings"
        )
        val knownKey = known.keys.firstOrNull { it == s }
            ?: known.keys.firstOrNull { s.contains(it) && it.length >= 4 }
        if (knownKey != null) {
            val pkg = known[knownKey]!!
            if (explicitLaunchIntent(pkg) != null) {
                android.util.Log.i("AxiomMain", "App known match: \'$s\' -> $pkg")
                return makeResult(pkg, knownKey)
            }
        }

        return null
    }


    private fun runProactiveSuggestion() {
        val svc = axiomService
        if (svc == null || !svc.isReady()) return
        Thread {
            svc.collectSensors()
            val raw  = svc.proactiveCheck()
            val json = runCatching { JSONObject(raw) }.getOrNull()
            runOnUiThread { handleResult(json, isProactive = true) }
        }.start()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Result handling
    // ════════════════════════════════════════════════════════════════════════
    private fun handleResult(json: JSONObject?, isProactive: Boolean = false, isVoiceInput: Boolean = false) {
        val svc   = axiomService
        val ready = svc?.isReady() == true
        tvStatus.text = if (ready) "✅  Axiom Ready" else "⚠️  Keyword-only mode"
        setProcessingState(false)   // always re-enable buttons when a result arrives
        // Update learning bar and world model after every inference - adapter stats change
        refreshLearningBar()
        refreshWorldModelCard()

        if (json == null) return

        // ── App launch ────────────────────────────────────────────────────────
        if (json.optString("object") == "APP_LAUNCH") {
            val pkg      = json.optString("android_intent", "").removePrefix("app:")
            val appLabel = json.optString("app_label", "app").replaceFirstChar { it.uppercase() }
            if (pkg.isEmpty()) return
            tvLastAction.text = "Opening $appLabel..."
            tvConfidence.text = "📱 App"
            cardLastResult.visibility = View.VISIBLE
            cardContext?.visibility = View.GONE
            // Use explicit component intent - the only approach that works on MIUI
            val launchIntent = explicitLaunchIntent(pkg)
            if (launchIntent != null) {
                android.util.Log.i("AxiomMain", "handleResult APP_LAUNCH: $pkg via ${launchIntent.component}")
                runCatching { startActivity(launchIntent) }.onFailure {
                    tvLastAction.text = "Couldn't open $appLabel"
                    android.util.Log.e("AxiomMain", "APP_LAUNCH failed: ${it.message}")
                }
                // Record this app open so Axiom learns the habit
                axiomService?.recordAppOpen(pkg, appLabel)
            } else {
                tvLastAction.text = "$appLabel isn't installed"
                android.util.Log.w("AxiomMain", "APP_LAUNCH: no launcher entry for $pkg")
            }
            return
        }

        // ── Foreground intent launch (alarm, reminder, email, etc.) ──────────
        // AxiomService cannot start activities reliably on many ROMs (MIUI, HyperOS,
        // ColorOS, etc.) due to Android 10+ background activity launch restrictions.
        // Instead it returns LAUNCH_INTENT JSON and we fire startActivity() here
        // from MainActivity, which has a valid foreground window token.
        if (json.optString("object") == "LAUNCH_INTENT") {
            val reply  = json.optString("reply", "Done.")
            val action = json.optString("action", "")
            val pkg    = json.optString("pkg", "").takeIf { it.isNotEmpty() }
            val extras = json.optJSONObject("extras")
            val isAlarm = action == android.provider.AlarmClock.ACTION_SET_ALARM ||
                    action == android.provider.AlarmClock.ACTION_SHOW_ALARMS

            tvLastAction.text = reply
            tvConfidence.text = if (isAlarm) "⏰ Axiom" else "🚀 Axiom"
            cardLastResult.visibility = View.VISIBLE
            cardContext?.visibility   = View.GONE
            if (isVoiceInput) speakReply(reply)

            if (action.isNotEmpty()) {
                fun buildIntent(forcePkg: String?) = Intent(action).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    forcePkg?.let { `package` = it }
                    if (action == android.provider.AlarmClock.ACTION_SET_ALARM) {
                        // Use AlarmClock constants — generic key loop below won't cover these
                        extras?.optInt("hour",    -1).takeIf { it != -1 }?.let {
                            putExtra(android.provider.AlarmClock.EXTRA_HOUR, it) }
                        extras?.optInt("minutes", -1).takeIf { it != -1 }?.let {
                            putExtra(android.provider.AlarmClock.EXTRA_MINUTES, it) }
                        extras?.optString("message")?.let {
                            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, it) }
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                    } else {
                        extras?.keys()?.forEach { key ->
                            when (val v = extras.get(key)) {
                                is Int     -> putExtra(key, v)
                                is Boolean -> putExtra(key, v)
                                is String  -> putExtra(key, v)
                                else       -> putExtra(key, v.toString())
                            }
                        }
                    }
                }

                // pkg is pre-resolved on the background infer() thread — no PM calls here.
                // Try pkg-locked first (specific clock app), then implicit fallback.
                val fired = runCatching { startActivity(buildIntent(pkg)); true }.getOrDefault(false)
                        || runCatching { startActivity(buildIntent(null)); true }.getOrDefault(false)

                android.util.Log.i("AxiomMain", "LAUNCH_INTENT action=$action pkg=$pkg fired=$fired")

                // Show OS-restriction notice on every alarm until user taps "Got it".
                // We can't detect a silent OS block (intent fires but Clock never opens),
                // so we always warn and give the user an "Open Clock" escape hatch.
                if (isAlarm) {
                    val prefs = getSharedPreferences(PREFS_MAIN, MODE_PRIVATE)
                    if (!prefs.getBoolean("alarm_os_notice_shown", false)) {
                        showAlarmOsNotice(pkg)  // pkg pre-resolved on bg thread, no PM calls here
                    } else if (!fired) {
                        runCatching {
                            android.widget.Toast.makeText(this,
                                "Clock app didn't open — please set the alarm manually.",
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            return
        }

        // ── Conversational reply ──────────────────────────────────────────────
        if (json.optString("object") == "NONE") {
            val reply = json.optString("reply", "")
            if (reply.isNotEmpty()) {
                tvLastAction.text  = reply
                tvConfidence.text  = "💬 Axiom"
                cardLastResult.visibility = View.VISIBLE
                cardContext?.visibility = View.GONE
                // If reply looks like "I don't know" / "I'm not sure", offer web search
                val uncertain = reply.lowercase().let {
                    it.contains("don't know") || it.contains("not sure") ||
                            it.contains("can't help") || it.contains("cannot help") ||
                            it.contains("unable to") || it.contains("i'm not") ||
                            it.contains("i am not") || it.contains("no information") ||
                            it.contains("try searching") || it.contains("look it up")
                }
                if (isVoiceInput) speakReply(reply)
                if (uncertain) offerWebSearch(lastUserPrompt)
                return
            }
            if (isProactive) tvStatus.text = "💤  Nothing to suggest right now"
            return
        }

        val action        = json.optString("object")
        val confidence    = json.optDouble("confidence", 0.0).toFloat()
        val androidAction = json.optString("android_intent")
        val usedNeural    = json.optBoolean("used_neural", false)
        val adapterN      = json.optInt("adapter_updates", 0)

        val modeTag = when {
            adapterN >= 50 && !usedNeural -> "⚡ Instant"
            usedNeural                    -> "🧠 Neural"
            else                          -> "🔑 Keyword"
        }

        val pct   = (confidence * 100).toInt()
        val label = friendlyLabel(action)
        tvStatus.text = "$modeTag - $pct% - $label"

        if (confidence < MIN_CONFIDENCE) {
            tvStatus.text = "🤔  Low confidence for $label - try rephrasing"
            return
        }

        lastIntent = action
        tvLastAction.text = label
        tvConfidence.text = "$pct%  -  $modeTag"
        cardLastResult.visibility = View.VISIBLE

        if (confidence >= 0.80f && !isProactive) {
            launchIntent(action, androidAction)
            svc?.feedback(action, true)
            if (isVoiceInput) speakReply("Opening $label")
        } else {
            confirmAndLaunch(action, androidAction, confidence)
        }
    }

    private fun confirmAndLaunch(action: String, androidAction: String, confidence: Float) {
        val label = friendlyLabel(action)
        val pct   = (confidence * 100).toInt()
        AlertDialog.Builder(this)
            .setTitle("Axiom Suggests")
            .setMessage("I'm $pct% sure you want to open $label. Is that right?")
            .setPositiveButton("Open") { _, _ ->
                launchIntent(action, androidAction)
                axiomService?.feedback(action, true)
                tvStatus.text = "✅  Opened"
            }
            .setNegativeButton("Not this") { _, _ ->
                axiomService?.feedback(action, false)
                showHumanLabelDialog(action)
            }
            .show()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Human label dialog (two-level category picker)
    // ════════════════════════════════════════════════════════════════════════
    private fun showHumanLabelDialog(wrongAction: String) {
        data class IntentItem(val action: String, val label: String)
        data class Category(val name: String, val items: List<IntentItem>)

        val categories = listOf(
            Category("🔋 Battery & Power", listOf(
                IntentItem("ACTION_BATTERY_SAVER",        "Battery Saver"),
                IntentItem("ACTION_BATTERY_USAGE",        "Battery Usage"),
                IntentItem("ACTION_BATTERY_HEALTH",       "Battery Health"),
                IntentItem("ACTION_CHARGING_SETTINGS",    "Charging Settings"),
                IntentItem("ACTION_ADAPTIVE_BATTERY",     "Adaptive Battery"),
                IntentItem("ACTION_BATTERY_OPTIMIZATION", "Battery Optimization"),
                IntentItem("ACTION_BATTERY_PERCENTAGE",   "Battery Percentage"),
            )),
            Category("💾 Storage & Files", listOf(
                IntentItem("ACTION_MANAGE_STORAGE", "Storage Manager"),
                IntentItem("ACTION_MANAGE_APPS",    "App Manager"),
                IntentItem("ACTION_CLEAR_CACHE",    "Clear Cache"),
                IntentItem("ACTION_SD_CARD",        "SD Card"),
                IntentItem("ACTION_FILES",          "Files"),
                IntentItem("ACTION_BACKUP",         "Backup"),
                IntentItem("ACTION_RESTORE",        "Restore"),
            )),
            Category("🖥️ Display & Screen", listOf(
                IntentItem("ACTION_BRIGHTNESS",        "Brightness"),
                IntentItem("ACTION_DARK_MODE",         "Dark Mode"),
                IntentItem("ACTION_SCREEN_TIMEOUT",    "Screen Timeout"),
                IntentItem("ACTION_FONT_SIZE",         "Font Size"),
                IntentItem("ACTION_SCREEN_RESOLUTION", "Resolution"),
                IntentItem("ACTION_REFRESH_RATE",      "Refresh Rate"),
                IntentItem("ACTION_ALWAYS_ON_DISPLAY", "Always On Display"),
                IntentItem("ACTION_BLUE_LIGHT_FILTER", "Blue Light Filter"),
                IntentItem("ACTION_WALLPAPER",         "Wallpaper"),
            )),
            Category("🔊 Sound & Audio", listOf(
                IntentItem("ACTION_SOUND_SETTINGS", "Sound Settings"),
                IntentItem("ACTION_DND",            "Do Not Disturb"),
                IntentItem("ACTION_RINGTONE",       "Ringtone"),
                IntentItem("ACTION_MEDIA_VOLUME",   "Media Volume"),
                IntentItem("ACTION_VIBRATION",      "Vibration"),
                IntentItem("ACTION_EQUALIZER",      "Equalizer"),
                IntentItem("ACTION_SPATIAL_AUDIO",  "Spatial Audio"),
                IntentItem("ACTION_DOLBY",          "Dolby Atmos"),
            )),
            Category("📶 Network & Connectivity", listOf(
                IntentItem("ACTION_WIFI_SETTINGS",      "WiFi"),
                IntentItem("ACTION_BLUETOOTH_SETTINGS", "Bluetooth"),
                IntentItem("ACTION_AIRPLANE_MODE",      "Airplane Mode"),
                IntentItem("ACTION_DATA_USAGE",         "Data Usage"),
                IntentItem("ACTION_MOBILE_NETWORK",     "Mobile Network"),
                IntentItem("ACTION_HOTSPOT",            "Hotspot"),
                IntentItem("ACTION_VPN",                "VPN"),
                IntentItem("ACTION_NFC",                "NFC"),
                IntentItem("ACTION_CAST",               "Cast / Mirror"),
                IntentItem("ACTION_NEARBY_SHARE",       "Nearby Share"),
            )),
            Category("🔒 Privacy & Security", listOf(
                IntentItem("ACTION_PRIVACY_SETTINGS",  "Privacy"),
                IntentItem("ACTION_LOCATION_SETTINGS", "Location"),
                IntentItem("ACTION_SECURITY_SETTINGS", "Security"),
                IntentItem("ACTION_APP_PERMISSIONS",   "App Permissions"),
                IntentItem("ACTION_FINGERPRINT",       "Fingerprint"),
                IntentItem("ACTION_FACE_UNLOCK",       "Face Unlock"),
                IntentItem("ACTION_SCREEN_LOCK",       "Screen Lock"),
                IntentItem("ACTION_APP_LOCK",          "App Lock"),
                IntentItem("ACTION_EMERGENCY_SOS",     "Emergency SOS"),
            )),
            Category("📱 Apps & System", listOf(
                IntentItem("ACTION_NOTIFICATION_SETTINGS", "Notifications"),
                IntentItem("ACTION_DEFAULT_APPS",          "Default Apps"),
                IntentItem("ACTION_RUNNING_APPS",          "Running Apps"),
                IntentItem("ACTION_APP_INFO",              "App Info"),
                IntentItem("ACTION_INSTALL_APPS",          "Install Apps"),
                IntentItem("ACTION_DEVELOPER_OPTIONS",     "Developer Options"),
                IntentItem("ACTION_SYSTEM_UPDATE",         "System Update"),
                IntentItem("ACTION_ABOUT_PHONE",           "About Phone"),
                IntentItem("ACTION_RESET",                 "Reset"),
            )),
            Category("♿ Accessibility & Input", listOf(
                IntentItem("ACTION_ACCESSIBILITY",     "Accessibility"),
                IntentItem("ACTION_FONT_SCALING",      "Font Scaling"),
                IntentItem("ACTION_COLOUR_CORRECTION", "Colour Correction"),
                IntentItem("ACTION_TALKBACK",          "TalkBack"),
                IntentItem("ACTION_KEYBOARD",          "Keyboard"),
                IntentItem("ACTION_LANGUAGE",          "Language"),
                IntentItem("ACTION_SPELL_CHECK",       "Spell Check"),
                IntentItem("ACTION_VOICE_INPUT",       "Voice Input"),
            )),
            Category("📷 Camera & Media", listOf(
                IntentItem("ACTION_CAMERA_SETTINGS", "Camera Settings"),
                IntentItem("ACTION_PHOTO_QUALITY",   "Photo Quality"),
                IntentItem("ACTION_VIDEO_SETTINGS",  "Video Settings"),
                IntentItem("ACTION_SCREENSHOT",      "Screenshot"),
                IntentItem("ACTION_SCREEN_RECORDER", "Screen Recorder"),
                IntentItem("ACTION_GALLERY",         "Gallery"),
                IntentItem("ACTION_MEDIA_CONTROLS",  "Media Controls"),
            )),
            Category("❤️ Health & Device", listOf(
                IntentItem("ACTION_ALARM",            "Alarm / Clock"),
                IntentItem("ACTION_HEALTH_SETTINGS",  "Health"),
                IntentItem("ACTION_STEPS_COUNTER",    "Steps Counter"),
                IntentItem("ACTION_SLEEP_SETTINGS",   "Sleep"),
                IntentItem("ACTION_STRESS_MONITOR",   "Stress Monitor"),
                IntentItem("ACTION_GAME_MODE",        "Game Mode"),
                IntentItem("ACTION_DIGITAL_WELLBEING","Digital Wellbeing"),
                IntentItem("ACTION_FOCUS_MODE",       "Focus Mode"),
                IntentItem("ACTION_KIDS_MODE",        "Kids Mode"),
                IntentItem("ACTION_SMART_HOME",       "Smart Home"),
                IntentItem("ACTION_ROUTINES",         "Routines"),
            )),
            // ── App category: tapping this opens the scrollable installed-app picker
            Category("📱 Open an App", emptyList()),
        )

        val catLabels = categories.map { cat ->
            val hasWrong = cat.items.any { it.action == wrongAction }
            if (hasWrong) "${cat.name}  ← guessed here" else cat.name
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("What did you want?")
            .setItems(catLabels) { _, catIdx ->
                val cat = categories[catIdx]

                // ── App picker category ───────────────────────────────────────
                if (cat.name.startsWith("📱 Open an App")) {
                    showInstalledAppPicker(wrongAction)
                    return@setItems
                }

                val items = cat.items.filter { it.action != wrongAction }
                val itemLabels = (items.map { it.label } + "❌ Nothing - just exploring").toTypedArray()

                AlertDialog.Builder(this)
                    .setTitle(cat.name)
                    .setItems(itemLabels) { _, itemIdx ->
                        if (itemIdx == items.size) {
                            tvStatus.text = "📝 Got it"
                            return@setItems
                        }
                        val chosen = items[itemIdx]
                        axiomService?.feedback(chosen.action, true)
                        tvStatus.text = "📚 Learned: ${chosen.label}"
                        AlertDialog.Builder(this)
                            .setTitle("Open ${chosen.label}?")
                            .setPositiveButton("Yes") { _, _ ->
                                launchIntent(chosen.action, axiomService?.resolveAndroidAction(chosen.action) ?: "")
                            }
                            .setNegativeButton("Not now") { _, _ -> }
                            .show()
                    }
                    .setNegativeButton("Back") { _, _ -> showHumanLabelDialog(wrongAction) }
                    .show()
            }
            .setNegativeButton("Skip") { _, _ -> tvStatus.text = "✅  Axiom Ready" }
            .show()
    }

    // ── Feature 3: Scrollable installed-app picker with learning ─────────────
    private fun showInstalledAppPicker(wrongAction: String) {
        tvStatus.text = "📱 Loading apps..."

        Thread {
            val pm = packageManager
            data class AppEntry(val label: String, val pkg: String)
            val entries = mutableListOf<AppEntry>()

            // Two-pass strategy to get ALL user-visible apps regardless of ROM restrictions:
            //
            // Pass 1 - User-installed apps (FLAG_SYSTEM not set):
            //   Always included unconditionally. Every user-installed app is launchable
            //   by definition. No permission or activity check needed.
            //
            // Pass 2 - System apps that ARE in the launcher cache (the 18 the ROM allows):
            //   These are system apps the user can see (Settings, Calculator, Camera etc.).
            //   We include them because users may want to open them too.
            //
            // Launch strategy (in click handler):
            //   1. getLaunchIntentForPackage - works for system apps and some user apps
            //   2. Explicit ComponentName from launcher cache - for MIUI-blocked cases
            //   3. Intent with just the package name - last resort, Android resolves it

            // MIUI marks many user-installed apps as FLAG_SYSTEM (pre-installs, ROM bloatware,
            // even Play Store apps on some builds). FLAG_SYSTEM filtering is therefore useless.
            //
            // Only reliable filter: does the app have a human-readable label different from
            // its package name? Background services, providers, and hidden system internals
            // either have no label or use their package name as label. Real user-facing apps
            // always have a proper name like "Spotify", "WhatsApp", "Clash of Clans" etc.
            //
            // We include ALL packages with a real label. The deduplication set handles
            // cases where a package appears more than once.
            val allApps = runCatching {
                pm.getInstalledPackages(0)
            }.getOrNull() ?: emptyList()

            android.util.Log.d("AxiomMain", "Total packages on device: ${allApps.size}")
            val seen = mutableSetOf<String>()

            for (pkgInfo in allApps) {
                val pkg     = pkgInfo.packageName ?: continue
                val appInfo = pkgInfo.applicationInfo ?: continue
                if (!seen.add(pkg)) continue

                // Skip packages with no code (pure resource packages, stubs)
                if (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_HAS_CODE == 0) continue

                val label = runCatching {
                    pm.getApplicationLabel(appInfo).toString().trim()
                }.getOrNull() ?: continue

                // Skip if label is blank, is the package name itself, looks like a
                // technical identifier (contains dots but no spaces), or is very short
                if (label.isBlank()) continue
                if (label == pkg) continue
                if (label.length < 2) continue
                // Package-name-style labels like "com.android.foo" - not user-facing
                if (label.contains('.') && !label.contains(' ') && label.contains("com.")) continue

                entries.add(AppEntry(label, pkg))
            }

            entries.sortBy { it.label.lowercase() }
            android.util.Log.d("AxiomMain", "App picker: ${entries.size} apps from ${allApps.size} packages")

            runOnUiThread {
                tvStatus.text = "✅  Axiom Ready"
                if (entries.isEmpty()) {
                    android.widget.Toast.makeText(this, "No apps found", android.widget.Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                val labels = entries.map { it.label }.toTypedArray()

                // Scrollable list dialog
                val listView = android.widget.ListView(this)
                listView.adapter = android.widget.ArrayAdapter(
                    this, android.R.layout.simple_list_item_1, labels)

                val dialog = android.app.AlertDialog.Builder(this)
                    .setTitle("📱 Choose an app")
                    .setView(listView)
                    .setNegativeButton("Back") { _, _ -> showHumanLabelDialog(wrongAction) }
                    .create()

                listView.setOnItemClickListener { _, _, idx, _ ->
                    dialog.dismiss()
                    val chosen = entries[idx]

                    axiomService?.recordAppOpen(chosen.pkg, chosen.label)
                    android.util.Log.i("AxiomMain", "Picker: ${chosen.label} -> ${chosen.pkg}")
                    tvStatus.text = "📚 Learned: ${chosen.label}"

                    // Launch intent fallback chain:
                    // 1. Explicit ComponentName from launcher cache (best - works on MIUI)
                    // 2. getLaunchIntentForPackage (works for system apps + non-MIUI)
                    // 3. ACTION_MAIN with setPackage (last resort)
                    val launched = runCatching {
                        val cacheRi = getLauncherCache()[chosen.pkg]
                        val intent = if (cacheRi != null) {
                            android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                                component = android.content.ComponentName(
                                    chosen.pkg, cacheRi.activityInfo.name)
                                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                        android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                            }
                        } else {
                            pm.getLaunchIntentForPackage(chosen.pkg)?.apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                        android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                            } ?: android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                                setPackage(chosen.pkg)
                                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                        android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                            }
                        }
                        startActivity(intent)
                        true
                    }.getOrDefault(false)

                    if (launched) {
                        tvLastAction.text = "Opening ${chosen.label}..."
                        tvConfidence.text = "📱 App (learned)"
                        cardLastResult.visibility = View.VISIBLE
                        cardContext?.visibility = View.GONE
                    } else {
                        android.widget.Toast.makeText(
                            this, "Couldn't open ${chosen.label}",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                dialog.show()
            }
        }.start()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  TTS - Text-to-Speech replies (voice mode only)
    // ════════════════════════════════════════════════════════════════════════

    private fun initTts() {
        tts = android.speech.tts.TextToSpeech(this) { status ->
            ttsReady = (status == android.speech.tts.TextToSpeech.SUCCESS)
            if (ttsReady) {
                tts?.language = java.util.Locale.getDefault()
                tts?.setSpeechRate(1.05f)   // slightly faster - natural for assistant
                android.util.Log.i("AxiomTTS", "TTS ready")
            } else {
                android.util.Log.w("AxiomTTS", "TTS init failed: $status")
            }
        }
    }

    private fun speakReply(text: String) {
        if (!ttsReady || tts == null) return
        // Truncate very long replies - TTS is for confirmations, not essays
        val toSpeak = if (text.length > 150) text.take(150) + "..." else text
        tts?.speak(toSpeak,
            android.speech.tts.TextToSpeech.QUEUE_FLUSH,
            null, "axiom_reply")
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Voice Input - uses Android's built-in SpeechRecognizer
    //  Same engine as the keyboard microphone. No extra model needed.
    //  Works offline if the user has offline speech pack installed
    //  (Settings → General management → Voice input → Offline speech recognition).
    //  Supports Hindi, English, and any language the device speech engine handles.
    // ════════════════════════════════════════════════════════════════════════

    private fun onMicButtonTapped() {
        if (isListening) {
            // Second tap cancels listening
            stopVoiceInput()
            return
        }
        // Check RECORD_AUDIO permission - request if missing
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        voiceTriggered = true    // mic = voice mode, enable TTS reply
        startVoiceInput()
    }

    private fun startVoiceInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this,
                "Speech recognition not available on this device",
                Toast.LENGTH_LONG).show()
            return
        }

        // Create fresh recognizer each time - reusing across sessions causes errors
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                btnMic.setImageResource(android.R.drawable.ic_btn_speak_now)
                tvStatus.text = "🎤  Listening..."
                inputField.hint = "Speak now..."
            }

            override fun onBeginningOfSpeech() {
                // User started speaking - show waveform feedback
                tvStatus.text = "🎤  Hearing you..."
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Volume level - could animate mic button here if desired
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // Show partial transcript in input field as user speaks
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                inputField.setText(partial)
                inputField.setSelection(partial.length)
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                btnMic.setImageResource(android.R.drawable.ic_btn_speak_now)
                inputField.hint = "Type or tap mic..."

                val matches = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches.isNullOrEmpty()) {
                    tvStatus.text = "✅  Axiom Ready"
                    return
                }

                // Use the highest-confidence result (first in list)
                val spoken = matches[0].trim()
                android.util.Log.i("AxiomVoice", "Heard: '$spoken'")

                if (spoken.isBlank()) {
                    tvStatus.text = "✅  Axiom Ready"
                    return
                }

                // Put transcript in input field so user can see + edit it
                inputField.setText(spoken)
                inputField.setSelection(spoken.length)

                // Fire inference immediately - same path as typing and pressing Send
                runInference(spoken)
            }

            override fun onError(error: Int) {
                isListening = false
                btnMic.setImageResource(android.R.drawable.ic_btn_speak_now)
                inputField.hint = "Type or tap mic..."
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH        -> "Didn't catch that - try again"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT  -> "No speech detected"
                    SpeechRecognizer.ERROR_AUDIO           -> "Audio error - check microphone"
                    SpeechRecognizer.ERROR_NETWORK         -> "Network error - use offline speech pack"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout - use offline speech pack"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech engine busy - try again"
                    else -> "Speech error ($error)"
                }
                tvStatus.text = "⚠️  $msg"
                android.util.Log.w("AxiomVoice", "SpeechRecognizer error: $error - $msg")
            }

            override fun onEndOfSpeech() {
                isListening = false
                btnMic.setImageResource(android.R.drawable.ic_btn_speak_now)
                tvStatus.text = "⏳  Processing speech..."
                inputField.hint = "Type or tap mic..."
            }

            // Unused callbacks
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // Free-form speech - not constrained to a word list
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // Prefer offline engine - no network needed if offline pack is installed
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Return up to 3 alternatives (we use the first/best)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Show partial results as user speaks
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Prompt shown in the speech overlay (some devices show this)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell Axiom what to do...")
        }

        runCatching {
            speechRecognizer?.startListening(intent)
        }.onFailure {
            tvStatus.text = "⚠️  Could not start voice input"
            android.util.Log.e("AxiomVoice", "startListening failed: ${it.message}")
        }
    }

    private fun stopVoiceInput() {
        speechRecognizer?.stopListening()
        isListening = false
        btnMic.setImageResource(android.R.drawable.ic_btn_speak_now)
        tvStatus.text = "✅  Axiom Ready"
        inputField.hint = "Type or tap mic..."
    }

    // ── Feature 2: Web search fallback ──────────────────────────────────────
    private fun offerWebSearch(query: String) {
        if (query.isBlank()) return
        android.app.AlertDialog.Builder(this)
            .setTitle("Search the web?")
            .setMessage("Axiom isn't sure about that. Search for \"$query\"?")
            .setPositiveButton("Search") { _, _ -> openWebSearch(query) }
            .setNegativeButton("No thanks") { _, _ -> }
            .show()
    }

    private fun openWebSearch(query: String) {
        val uri = android.net.Uri.parse(
            "https://www.google.com/search?q=" + android.net.Uri.encode(query))
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
        runCatching { startActivity(intent) }.onFailure {
            android.widget.Toast.makeText(this, "No browser found", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ── Feature 1: Show dream summary ────────────────────────────────────────
    private fun showDreamSummary() {
        val svc = axiomService ?: return
        val summary = svc.getLastDreamSummary()
        if (summary == null) {
            android.app.AlertDialog.Builder(this)
                .setTitle("🌙 Dream Learning")
                .setMessage("No dream session yet. Connect charger overnight - Axiom learns while you sleep.")
                .setPositiveButton("OK") { _, _ -> }
                .show()
            return
        }
        showDreamSummaryFull(summary)
    }

    private fun friendlyLabel(action: String) =
        action.removePrefix("ACTION_").replace("_", " ")
            .lowercase().replaceFirstChar { it.uppercase() }


    // ── Sound / Volume helpers ────────────────────────────────────────────────

    /**
     * Pops up the system volume panel - exactly what the hardware volume buttons do.
     * Works on ALL Android devices and ROMs. No Settings page, instant result.
     */
    private fun openVolumePanel() {
        runCatching {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            // ADJUST_SAME + FLAG_SHOW_UI = show panel without changing volume
            am.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.ADJUST_SAME,
                android.media.AudioManager.FLAG_SHOW_UI
            )
        }.onFailure {
            // Fallback to sound settings if AudioManager fails
            runCatching {
                startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /**
     * Opens Sound Settings - tries OEM-specific intents before the generic one,
     * because Settings.ACTION_SOUND_SETTINGS opens the wrong page on MIUI/Samsung.
     */
    private fun openSoundSettings() {
        val candidates = listOf(
            // Stock Android / Pixel
            "android.settings.SOUND_SETTINGS",
            // MIUI (Xiaomi / Redmi / POCO)
            "miui.intent.action.SOUND_SETTINGS",
            // Samsung One UI
            "android.settings.SOUND_SETTINGS",
            // Generic fallback
            "android.settings.SETTINGS"
        )
        for (action in candidates) {
            val launched = runCatching {
                startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            if (launched) return
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Android Intent Launcher
    // ════════════════════════════════════════════════════════════════════════
    private fun launchIntent(action: String, androidAction: String) {
        // Wrap intentFor() itself - some Intent constructors throw on bad action strings
        val intent: Intent = runCatching { intentFor(action, androidAction) }
            .getOrNull() ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Catch ActivityNotFoundException AND SecurityException.
        // SecurityException is thrown by SET_ALARM and certain Settings intents
        // on Xiaomi/Samsung ROMs - without catching it here the process is killed.
        val launched = runCatching { startActivity(intent) }.isSuccess
        if (!launched) {
            runCatching {
                startActivity(Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            // Guard Toast - window token may be invalid if activity is backgrounding
            runCatching {
                Toast.makeText(this, "Opened Settings - navigate to ${friendlyLabel(action)}",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun intentFor(action: String, androidAction: String): Intent? = when (action) {
        "ACTION_BATTERY_SAVER",
        "ACTION_BATTERY_HEALTH",
        "ACTION_POWER_MENU",
        "ACTION_CHARGING_SETTINGS",
        "ACTION_ADAPTIVE_BATTERY"      -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        "ACTION_BATTERY_USAGE"         -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
        "ACTION_BATTERY_OPTIMIZATION"  -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        "ACTION_BATTERY_PERCENTAGE"    -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        "ACTION_MANAGE_STORAGE",
        "ACTION_CLEAR_CACHE",
        "ACTION_FILES"                 -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        "ACTION_MANAGE_APPS",
        "ACTION_RUNNING_APPS",
        "ACTION_APP_INFO"              -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
        "ACTION_SD_CARD"               -> Intent(Settings.ACTION_MEMORY_CARD_SETTINGS)
        "ACTION_BACKUP",
        "ACTION_RESTORE"               -> Intent(Settings.ACTION_PRIVACY_SETTINGS)
        "ACTION_INSTALL_APPS"          -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .also { it.data = Uri.fromParts("package", packageName, null) }
        "ACTION_DISPLAY_SETTINGS",
        "ACTION_BRIGHTNESS",
        "ACTION_SCREEN_TIMEOUT",
        "ACTION_SCREEN_RESOLUTION",
        "ACTION_ALWAYS_ON_DISPLAY",
        "ACTION_SCREENSHOT",
        "ACTION_SCREEN_RECORDER",
        "ACTION_FLASHLIGHT",
        "ACTION_QUICK_SETTINGS",
        "ACTION_SPLIT_SCREEN"          -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        "ACTION_DARK_MODE"             -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        "ACTION_FONT_SIZE"             -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "ACTION_REFRESH_RATE"          -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        "ACTION_BLUE_LIGHT_FILTER"     -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        "ACTION_WALLPAPER"             -> Intent(Intent.ACTION_SET_WALLPAPER)
        // Media volume → show live volume panel via AudioManager (no Settings page needed)
        "ACTION_MEDIA_VOLUME"          -> { openVolumePanel(); null }
        // Sound / ringtone / vibration → OEM-safe sound settings opener
        "ACTION_SOUND_SETTINGS",
        "ACTION_RINGTONE",
        "ACTION_VIBRATION"             -> { openSoundSettings(); null }
        // Equalizer / spatial audio / media player features → try music app first
        "ACTION_EQUALIZER",
        "ACTION_SPATIAL_AUDIO",
        "ACTION_DOLBY",
        "ACTION_MEDIA_CONTROLS"        -> launchAppOrFallback(
            listOf("com.spotify.music", "com.google.android.music",
                "com.apple.android.music", "com.amazon.mp3",
                "com.soundcloud.android", "com.miui.player",
                "com.sec.android.app.music"),
            Intent(Settings.ACTION_SOUND_SETTINGS))
        "ACTION_DND",
        "ACTION_FOCUS_MODE"            -> launchAppOrFallback(
            listOf("com.forestapp.Forest", "com.onetouchapp.one", "io.finch.android"),
            Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS))
        "ACTION_SLEEP_SETTINGS"        -> launchAppOrFallback(
            listOf("com.urbandroid.sleep", "com.relaxio.calmsleep"),
            Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS))
        "ACTION_WIFI_SETTINGS",
        "ACTION_WIFI_DIRECT",
        "ACTION_NEARBY_SHARE"          -> Intent(Settings.ACTION_WIFI_SETTINGS)
        "ACTION_BLUETOOTH_SETTINGS"    -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        "ACTION_AIRPLANE_MODE"         -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        "ACTION_DATA_USAGE"            -> Intent(Settings.ACTION_DATA_USAGE_SETTINGS)
        "ACTION_MOBILE_NETWORK"        -> Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)
        "ACTION_HOTSPOT"               -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
        "ACTION_VPN"                   -> launchAppOrFallback(
            listOf("com.nordvpn.android", "com.expressvpn.vpn",
                "com.protonvpn.android", "com.privateinternetaccess.android"),
            Intent(Settings.ACTION_VPN_SETTINGS))
        "ACTION_NFC"                   -> Intent(Settings.ACTION_NFC_SETTINGS)
        "ACTION_CAST"                  -> launchAppOrFallback(
            listOf("com.google.android.apps.chromecast.app",
                "com.sec.android.smartmirroring", "com.milink.service"),
            Intent(Settings.ACTION_CAST_SETTINGS))
        "ACTION_PRIVACY_SETTINGS"      -> Intent(Settings.ACTION_PRIVACY_SETTINGS)
        "ACTION_CAMERA_SETTINGS",
        "ACTION_PHOTO_QUALITY",
        "ACTION_VIDEO_SETTINGS"        -> launchAppOrFallback(
            listOf("com.google.android.GoogleCamera", "com.sec.android.app.camera",
                "com.miui.camera", "com.oneplus.camera"),
            Intent(Settings.ACTION_PRIVACY_SETTINGS))
        "ACTION_LOCATION_SETTINGS"     -> launchAppOrFallback(
            listOf("com.google.android.apps.maps", "com.waze", "com.here.app.maps"),
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        "ACTION_COMPASS"               -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        "ACTION_SECURITY_SETTINGS",
        "ACTION_TWO_FACTOR_AUTH"       -> launchAppOrFallback(
            listOf("com.google.android.apps.authenticator2", "com.authy.authy",
                "com.bitwarden.mobile", "com.onepassword.android"),
            Intent(Settings.ACTION_SECURITY_SETTINGS))
        "ACTION_FINGERPRINT",
        "ACTION_FACE_UNLOCK",
        "ACTION_SCREEN_LOCK",
        "ACTION_APP_LOCK",
        "ACTION_EMERGENCY_SOS"         -> Intent(Settings.ACTION_SECURITY_SETTINGS)
        "ACTION_APP_PERMISSIONS"       -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .also { it.data = Uri.fromParts("package", packageName, null) }
        "ACTION_DEVICE_ADMIN"          -> Intent(Settings.ACTION_SECURITY_SETTINGS)
        "ACTION_NOTIFICATION_SETTINGS" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .also { it.putExtra(Settings.EXTRA_APP_PACKAGE, packageName) }
        "ACTION_DEFAULT_APPS"          -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        "ACTION_KIDS_MODE"             -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
        "ACTION_DEVELOPER_OPTIONS"     -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        "ACTION_SYSTEM_UPDATE"         -> Intent("android.settings.SYSTEM_UPDATE_SETTINGS")
        "ACTION_ABOUT_PHONE"           -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
        "ACTION_RESET"                 -> Intent(Settings.ACTION_SETTINGS)
        "ACTION_SEARCH_SETTINGS"       -> Intent(Settings.ACTION_SETTINGS)
        "ACTION_ACCESSIBILITY",
        "ACTION_FONT_SCALING",
        "ACTION_COLOUR_CORRECTION",
        "ACTION_TALKBACK",
        "ACTION_GESTURES"              -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "ACTION_KEYBOARD",
        "ACTION_SPELL_CHECK",
        "ACTION_VOICE_INPUT"           -> Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        "ACTION_LANGUAGE"              -> Intent(Settings.ACTION_LOCALE_SETTINGS)
        "ACTION_ALARM"                 -> openClockApp()
        "ACTION_DATE_TIME",
        "ACTION_TIMEZONE",
        "ACTION_24H_FORMAT",
        "ACTION_AUTO_TIME",
        "ACTION_CALENDAR_SETTINGS"     -> Intent(Settings.ACTION_DATE_SETTINGS)
        "ACTION_GALLERY"               -> launchAppOrFallback(
            listOf("com.google.android.apps.photos",
                "com.miui.gallery", "com.sec.android.gallery3d"),
            Intent(Intent.ACTION_VIEW).also { it.type = "image/*" })
        "ACTION_GAME_MODE"             -> launchAppOrFallback(
            listOf("com.samsung.android.game.gamehome",
                "com.miui.gamebooster", "com.android.gamingmode"),
            Intent(Settings.ACTION_DISPLAY_SETTINGS))
        "ACTION_DIGITAL_WELLBEING"     -> launchAppOrFallback(
            listOf("com.google.android.apps.wellbeing", "com.samsung.android.forest"),
            Intent(Settings.ACTION_APPLICATION_SETTINGS))
        "ACTION_HEALTH_SETTINGS",
        "ACTION_STEPS_COUNTER",
        "ACTION_STRESS_MONITOR"        -> launchAppOrFallback(
            listOf("com.google.android.apps.fitness",
                "com.samsung.shealth", "com.xiaomi.hm.health"),
            Intent(Settings.ACTION_APPLICATION_SETTINGS))
        "ACTION_SMART_HOME"            -> launchAppOrFallback(
            listOf("io.homeassistant.companion.android", "com.amazon.echo",
                "com.xiaomi.smarthome", "com.google.android.apps.chromecast.app"),
            Intent(Settings.ACTION_SETTINGS))
        "ACTION_ROUTINES"              -> launchAppOrFallback(
            listOf("com.samsung.android.app.routines", "net.dinglisch.android.taskerm"),
            Intent(Settings.ACTION_SETTINGS))
        else                           -> if (androidAction.isNotEmpty()) Intent(androidAction) else null
    }

    /**
     * Opens the device's Clock app.
     *
     * Strategy (in order):
     *   1. Query Android for any app that handles ACTION_MAIN + CATEGORY_LAUNCHER
     *      whose package name contains "clock", "alarm", "deskclock", or "time".
     *      This finds the OEM clock app on ANY device without a hardcoded list.
     *   2. Try known OEM package names as a fast-path backup.
     *   3. Last resort: open Date & Time settings (never crashes).
     */
    /**
     * Shown the first time an alarm/reminder is attempted.
     * On many devices (MIUI, HyperOS, ColorOS, stock Android 10+) the OS silently
     * blocks the Clock app from opening when triggered by a third-party app — even
     * when the intent fires without an exception. This is honest UX: tell the user
     * what happened and give them two easy escape hatches.
     */
    private fun showAlarmOsNotice(clockPkg: String?) {
        // All PM calls deferred to button tap — zero work on main thread at call time
        val isMiui = android.os.Build.MANUFACTURER.lowercase().let {
            it.contains("xiaomi") || it.contains("redmi") || it.contains("poco")
        }
        val brand = android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }

        val brandNote = if (isMiui)
            "\n\nOn $brand: Settings → Apps → Manage apps → Axiom → Autostart → Enable. " +
                    "Also add Axiom to the battery whitelist under Battery & Performance."
        else
            "\n\nOn $brand: check Settings → Apps → Special app access → Alarms & reminders, " +
                    "and make sure Axiom is allowed."

        android.app.AlertDialog.Builder(this)
            .setTitle("⚠️  Clock app didn't open")
            .setMessage(
                "Your device's OS ($brand) restricts third-party apps from opening " +
                        "the Clock app automatically. This is a manufacturer security policy — " +
                        "not a bug in Axiom.\n\n" +
                        "Axiom can still set alarms, but you may need to open the Clock app manually " +
                        "and confirm the alarm there." +
                        brandNote
            )
            .setPositiveButton("Open Clock now") { _, _ ->
                // Build launch intent here (button tap) — not at dialog creation time
                val clockLaunch = clockPkg?.let {
                    runCatching { packageManager.getLaunchIntentForPackage(it) }.getOrNull()
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { clockLaunch?.let { startActivity(it) } }
            }
            .setNegativeButton("Got it") { _, _ ->
                // Mark as acknowledged so we stop showing the full dialog
                getSharedPreferences(PREFS_MAIN, MODE_PRIVATE)
                    .edit().putBoolean("alarm_os_notice_shown", true).apply()
            }
            .setCancelable(false)
            .show()
    }

    private fun openClockApp(): Intent? {
        // Step 1: dynamic discovery - find any launcher activity whose package
        // name suggests it's a clock/alarm app. Works on every OEM without
        // needing a hardcoded list.
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val clockKeywords  = listOf("clock", "alarm", "deskclock", "time", "organizer")

        @Suppress("DEPRECATION")
        val allApps = packageManager.queryIntentActivities(launcherIntent, 0)

        for (keyword in clockKeywords) {
            val match = allApps.firstOrNull {
                it.activityInfo.packageName.lowercase().contains(keyword)
            }
            if (match != null) {
                val pkg = match.activityInfo.packageName
                android.util.Log.i("AxiomMain", "Clock found via query: $pkg")
                return packageManager.getLaunchIntentForPackage(pkg)
            }
        }

        // Step 2: known OEM packages as backup
        val knownPackages = listOf(
            "com.google.android.deskclock",
            "com.android.deskclock",
            "com.sec.android.app.clockpackage",
            "com.miui.clock",
            "com.oneplus.clock",
            "com.coloros.alarmclock",
            "com.realme.clock",
            "com.vivo.alarmclock",
            "com.asus.deskclock",
            "com.huawei.deskclock",
            "com.htc.android.worldclock",
            "com.sonyericsson.organizer",
            "com.motorola.clock",
            "com.transsion.deskclock"
        )
        for (pkg in knownPackages) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                android.util.Log.i("AxiomMain", "Clock found via known list: $pkg")
                return intent
            }
        }

        // Step 3: safe fallback - Date & Time settings always exists
        android.util.Log.w("AxiomMain", "No clock app found, falling back to date/time settings")
        return Intent(Settings.ACTION_DATE_SETTINGS)
    }

    private fun launchAppOrFallback(packages: List<String>, fallback: Intent): Intent {
        for (pkg in packages) {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) return launch
        }
        return fallback
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Usage analysis
    // ════════════════════════════════════════════════════════════════════════
    private fun runUsageAnalysis() {
        if (!UsageAnalyser.hasPermission(this)) {
            requestRuntimePermissions(); return
        }
        tvStatus.text = "⏳  Analysing usage..."
        Thread {
            val result = UsageAnalyser.analyse(this, days = 30)
            UsageAnalyser.saveEventLog(this, result)
            // feedIntoAxiom removed - adapter is trained from real accept/reject only
            runOnUiThread {
                tvStatus.text = "✅  ${result.mappedEvents} usage events recorded"
                Toast.makeText(this, UsageAnalyser.summaryStats(result), Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun maybeRunInitialUsageScan() {
        if (!UsageAnalyser.hasPermission(this)) return
        val prefs    = getSharedPreferences(ChargingReceiver.PREFS_NAME, MODE_PRIVATE)
        val lastScan = prefs.getLong(ChargingReceiver.KEY_LAST_USAGE_TS, 0L)
        val nowSec   = System.currentTimeMillis() / 1000
        if (nowSec - lastScan > 7L * 24 * 3600) runUsageAnalysis()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Dream cycle scheduling
    // ════════════════════════════════════════════════════════════════════════
    private fun scheduleDreamingCycle() {
        // Wrap entirely - setExactAndAllowWhileIdle requires SCHEDULE_EXACT_ALARM
        // on Android 12+ which needs user approval. A crash here kills the whole app.
        // Dream scheduling is non-critical; if it fails we just skip it silently.
        runCatching {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (bm.isCharging && battery >= 50) {
                ChargingReceiver.startDreamIfEligible(this)
            }
            runCatching { ChargingReceiver.scheduleDailyAlarms(this) }
                .onFailure { android.util.Log.w("Axiom", "Daily alarm skipped: ${it.message}") }
        }.onFailure {
            android.util.Log.e("Axiom", "scheduleDreamingCycle failed: ${it.message}")
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Notification channel
    // ════════════════════════════════════════════════════════════════════════
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                "axiom_proactive", "Axiom Suggestions",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Proactive suggestions from Axiom" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(chan)
        }
    }
}