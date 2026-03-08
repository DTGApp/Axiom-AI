package com.axiom.axiomnew

import android.app.*
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import android.provider.AlarmClock
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// ════════════════════════════════════════════════════════════════════════════════
//  AxiomService — Permanent always-on foreground service
//
//  Does NOT declare any external/JNI functions.
//  All native calls go through AxiomEngine (top-level singleton object),
//  which generates the correct JNI symbol names: Java_com_axiom_axiomnew_AxiomEngine_*
//
//  Autonomous loops (no user interaction required):
//    • Engine load     on service start, retries every 30 s until model file exists
//    • Sensor collect  every 5 min
//    • Proactive check every 15 min → notification if score ≥ 0.75
//    • Dream trigger   on each sensor tick when charging after 20:00
//    • Usage scan      every 6 h fallback if AlarmManager was killed by Doze
//
//  Survival:
//    START_STICKY   → Android OS restarts this service automatically if killed
//    BOOT_COMPLETED → ChargingReceiver.onReceive() starts us after every reboot
// ════════════════════════════════════════════════════════════════════════════════
class AxiomService : Service() {

    companion object {
        const val TAG                = "AxiomService"
        const val CHANNEL_BG         = "axiom_bg"
        const val CHANNEL_PROACTIVE  = "axiom_suggest"
        const val CHANNEL_INSIGHT    = "axiom_insight"
        const val CHANNEL_MODE       = "axiom_mode"
        const val NOTIF_SERVICE_ID   = 1
        const val NOTIF_PROACTIVE_ID = 2
        const val NOTIF_INSIGHT_ID   = 3
        const val NOTIF_MODE_ID      = 4

        // Wake word broadcast actions
        const val ACTION_WAKE_WORD_TRIGGERED = "com.axiom.axiomnew.WAKE_WORD"
        const val ACTION_PAUSE_WAKE_WORD     = "com.axiom.axiomnew.PAUSE_WAKE"
        const val ACTION_RESUME_WAKE_WORD    = "com.axiom.axiomnew.RESUME_WAKE"

        const val ACTION_START_DREAM = "com.axiom.axiomnew.START_DREAM"

        val WAKE_WORDS = listOf(
            "axiom", "hey axiom", "ok axiom", "axium", "hey axium",
            "axis", "hey axis"   // common mishears
        )

        // Phone-related vocabulary — presence means the input might be a phone request
        private val PHONE_VOCAB = setOf(
            "wifi","bluetooth","bt","airplane","hotspot","data","mobile","network","vpn","nfc",
            "battery","charging","power","saver","adaptive",
            "screen","brightness","display","dark mode","timeout","resolution","refresh","font",
            "sound","volume","ringtone","vibrat","dnd","notif","equalizer","dolby","spatial",
            "camera","photo","video","gallery","wallpaper","screenshot",
            "alarm","clock","timer","remind",
            "storage","cache","files","backup","restore","sd card",
            "location","gps","privacy","security","lock","fingerprint","face unlock","pin","password",
            "app","install","permission","developer","update","reset","about","accessibility",
            "keyboard","language","talkback","spell","voice input",
            "flashlight","compass","gestures","split screen","game mode","wellbeing","focus","kids",
            "health","steps","sleep","sos","nearby share","smart home","routines",
            "setting","open","turn on","turn off","enable","disable","change","show","go to",
            "can't","cannot","broken","not working","fix","help with"
        )
    }

    // ── State ──────────────────────────────────────────────────────────────────
    @Volatile var engineReady = false
        private set

    private val handler = Handler(Looper.getMainLooper())

    // ── v0.4 intelligence engines ──────────────────────────────────────────────
    private lateinit var batteryDoctor:    BatteryDoctor
    private lateinit var insightEngine:    InsightEngine
    private lateinit var phoneModeManager: PhoneModeManager
    private lateinit var lifeContextAssistant: LifeContextAssistant
    private lateinit var fileSearchEngine:     FileSearchEngine

    // ── Binder ────────────────────────────────────────────────────────────────
    inner class LocalBinder : Binder() {
        fun getService(): AxiomService = this@AxiomService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder


    // ── Lifecycle ──────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForegroundCompat()
        batteryDoctor    = BatteryDoctor(this)
        insightEngine    = InsightEngine(this)
        phoneModeManager = PhoneModeManager(this)
        lifeContextAssistant = LifeContextAssistant(this)
        fileSearchEngine     = FileSearchEngine(this)
        AppIntentRegistry.init(this)
        registerChargingReceiver()   // MIUI-safe: context receiver beats manifest receiver
        android.util.Log.i(TAG, "AxiomService created — loading engine")
        initEngineAsync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_DREAM) maybeTriggerDream()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterChargingReceiver()
        handler.removeCallbacksAndMessages(null)
        if (engineReady) {
            AxiomEngine.shutdownAxiomEngine()
            engineReady = false
        }
        android.util.Log.i(TAG, "AxiomService destroyed — scheduling hard restart")
        scheduleRestart()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        android.util.Log.i(TAG, "onTaskRemoved — scheduling restart")
        scheduleRestart()
    }

    private fun scheduleRestart() {
        runCatching {
            val pi = android.app.PendingIntent.getBroadcast(
                this, 999,
                Intent(this, ServiceRestartReceiver::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val triggerMs = System.currentTimeMillis() + 5_000L
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerMs, pi)
            } else {
                am.set(android.app.AlarmManager.RTC_WAKEUP, triggerMs, pi)
            }
            android.util.Log.i(TAG, "Restart alarm set for 5s from now")
        }.onFailure {
            android.util.Log.e(TAG, "scheduleRestart failed: ${it.message}")
        }
    }

    // ── Engine init ────────────────────────────────────────────────────────────
    // Runs on a background thread. Retries every 30 s until the model file
    // exists (MainActivity copies it from /sdcard on first launch).
    private fun initEngineAsync() {
        Thread {
            try {
                val modelFile = File(filesDir, "axiom_seed_q4.gguf")
                if (!modelFile.exists()) {
                    android.util.Log.w(TAG, "Model not copied yet — retry in 30 s")
                    handler.postDelayed({ initEngineAsync() }, 30_000L)
                    return@Thread
                }

                val ok = AxiomEngine.initAxiomEngine(
                    modelFile.absolutePath,
                    File(filesDir, "axiom_events.jsonl").absolutePath,
                    File(filesDir, "axiom_preds.csv").absolutePath,
                    File(filesDir, "axiom_adapter.bin").absolutePath
                )
                engineReady = ok
                AxiomEngine.ready = ok
                android.util.Log.i(TAG, "Engine init: ok=$ok")

                // Replay feedback queued while engine was down
                val pending = File(filesDir, NotifTapReceiver.PENDING_FB_FILE)
                if (pending.exists()) {
                    val n = AxiomEngine.replayPendingFeedback(pending.absolutePath)
                    if (n > 0) {
                        pending.delete()
                        android.util.Log.i(TAG, "Replayed $n pending feedback events")
                    }
                }

                // Start all background loops
                handler.post(sensorRunnable)
                handler.postDelayed(proactiveRunnable, 60_000L)  // first check 1 min in
                handler.postDelayed(usageScanRunnable, 5_000L)   // usage check 5 s in
                handler.postDelayed(insightRunnable, 2 * 60_000L)    // insight: 2 min after start
                handler.postDelayed(modeEvalRunnable, 30_000L)       // mode eval: 30 s after start
                handler.postDelayed(lifeContextRunnable, 3 * 60_000L) // life context: 3 min after start
                updateServiceNotif()

            } catch (e: Exception) {
                android.util.Log.e(TAG, "Engine init exception: ${e.message}")
                handler.postDelayed({ initEngineAsync() }, 60_000L)
            }
        }.start()
    }

    // ── In-service charging receiver ──────────────────────────────────────────
    // Registered in onCreate so MIUI cannot block it. POWER_CONNECTED via the
    // manifest ChargingReceiver is silently dropped on MIUI 14+ for non-system
    // apps. A context-registered receiver inside the running service always fires.
    private var chargingReceiver: android.content.BroadcastReceiver? = null

    private fun registerChargingReceiver() {
        if (chargingReceiver != null) return
        chargingReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                when (intent.action) {
                    android.content.Intent.ACTION_POWER_CONNECTED -> {
                        // Re-read battery from sticky broadcast.
                        // MIUI BUG: EXTRA_PLUGGED returns 0 even when charger is connected
                        // (seen on HyperOS / MIUI 14 with battery > 55%). Always also check
                        // EXTRA_STATUS — MIUI does NOT lie about that field.
                        val bs      = registerReceiver(null, android.content.IntentFilter(
                            android.content.Intent.ACTION_BATTERY_CHANGED))
                        val plugged = bs?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
                        val status  = bs?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS,  -1) ?: -1
                        val pct     = bs?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL,    0) ?: 0
                        // POWER_CONNECTED fired → charger is physically connected regardless of what
                        // EXTRA_PLUGGED says. Trust the intent action over the sticky broadcast field.
                        val isCharging = plugged > 0 ||
                                status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == android.os.BatteryManager.BATTERY_STATUS_FULL ||
                                intent.action == android.content.Intent.ACTION_POWER_CONNECTED  // always true here
                        android.util.Log.i(TAG,
                            "In-service POWER_CONNECTED: plugged=$plugged status=$status bat=$pct% → charging=$isCharging")
                        if (pct >= 20) {   // POWER_CONNECTED already proves charger is present
                            maybeTriggerDream()
                        }
                    }
                    android.content.Intent.ACTION_POWER_DISCONNECTED -> {
                        android.util.Log.i(TAG, "In-service POWER_DISCONNECTED")
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_POWER_CONNECTED)
            addAction(android.content.Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(chargingReceiver, filter)
        android.util.Log.i(TAG, "In-service charging receiver registered")
    }

    private fun unregisterChargingReceiver() {
        chargingReceiver?.let { runCatching { unregisterReceiver(it) } }
        chargingReceiver = null
    }

    // ── Loop 1: Sensor collection — every 5 minutes ───────────────────────────
    private val sensorRunnable = object : Runnable {
        override fun run() {
            collectSensors()
            handler.postDelayed(this, 5 * 60_000L)
        }
    }

    // Called by proactive loop and by MainActivity before inference
    fun collectSensors() {
        try {
            val bm       = getSystemService(BATTERY_SERVICE) as BatteryManager
            val battery  = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

            // MIUI BUG: bm.isCharging returns false when battery > ~55% because
            // MIUI enters trickle-charge mode and doesn't mark it as "charging".
            // Fix: read the sticky ACTION_BATTERY_CHANGED broadcast directly —
            // it always carries the correct plugged/status fields regardless of MIUI quirks.
            val batteryStatus: android.content.Intent? =
                registerReceiver(null, android.content.IntentFilter(
                    android.content.Intent.ACTION_BATTERY_CHANGED))
            val plugged   = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val status    = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging  = plugged > 0 ||
                    status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL

            val wifi = runCatching {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.getNetworkCapabilities(cm.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }.getOrDefault(false)

            val bt = runCatching {
                (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager)
                    .adapter?.isEnabled == true
            }.getOrDefault(false)

            val hour    = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val storage = runCatching {
                val s = StatFs(filesDir.absolutePath)
                s.availableBlocksLong * s.blockSizeLong / (1024L * 1024L)
            }.getOrDefault(-1L)

            // Read current brightness (0-255). -1 means auto/unknown.
            val brightness = runCatching {
                android.provider.Settings.System.getInt(
                    contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS
                )
            }.getOrDefault(-1)

            // Read current media volume as percentage (0-100). -1 = unknown.
            val volumePct = runCatching {
                val am = getSystemService(android.content.Context.AUDIO_SERVICE)
                        as android.media.AudioManager
                val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                if (max > 0) (cur * 100) / max else -1
            }.getOrDefault(-1)

            // Read ringer mode: 0=silent, 1=vibrate, 2=normal
            val ringerMode = runCatching {
                val am = getSystemService(android.content.Context.AUDIO_SERVICE)
                        as android.media.AudioManager
                am.ringerMode  // AudioManager.RINGER_MODE_*
            }.getOrDefault(-1)

            if (engineReady) {
                AxiomEngine.setSensorContext(battery, charging, wifi, bt, hour, storage,
                    brightness, volumePct, ringerMode)
            }

            android.util.Log.d(TAG, "Sensors: bat=$battery chg=$charging wifi=$wifi bt=$bt " +
                    "h=$hour bright=$brightness vol=$volumePct ringer=$ringerMode")

            // Dream triggers on charger connect with >= 20% battery.
            // Threshold lowered from 50%: after a long day the phone is often 20-40%.
            if (charging && battery >= 20) {
                maybeTriggerDream()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Sensor error: ${e.message}")
        }
    }

    // ── Loop 2: Proactive suggestion — every 15 minutes ──────────────────────
    // Posts a notification when temporal prior × adapter score ≥ 0.75.
    // Tap → opens real app (Spotify, Maps, etc.) + records accept.
    // Swipe away → records reject. Both signals train the adapter.
    private val proactiveRunnable = object : Runnable {
        override fun run() {
            if (engineReady) {
                Thread {
                    try {
                        collectSensors()
                        val raw    = AxiomEngine.proactiveSuggest()
                        val json   = JSONObject(raw)
                        val action = json.optString("object").takeIf { it != "NONE" }
                            ?: return@Thread
                        val score  = json.optDouble("confidence", 0.0)
                        if (score >= 0.75) {
                            android.util.Log.i(TAG, "Proactive: $action score=$score")
                            postProactiveNotif(action, score)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Proactive error: ${e.message}")
                    }
                }.start()
            }
            handler.postDelayed(this, 15 * 60_000L)
        }
    }

    // ── Loop 3: Usage scan fallback — every 6 hours ───────────────────────────
    // AlarmManager (ChargingReceiver, 03:00 daily) is the primary trigger.
    // This catches the case where Doze / battery optimisation killed the alarm.
    private val usageScanRunnable = object : Runnable {
        override fun run() {
            if (UsageAnalyser.hasPermission(this@AxiomService)) {
                val prefs   = getSharedPreferences(ChargingReceiver.PREFS_NAME, MODE_PRIVATE)
                val lastSec = prefs.getLong(ChargingReceiver.KEY_LAST_USAGE_TS, 0L)
                val nowSec  = System.currentTimeMillis() / 1000L
                val gapSec  = ChargingReceiver.MIN_USAGE_GAP_MS / 1000L
                if (nowSec - lastSec > gapSec) {
                    android.util.Log.i(TAG, "Service usage scan (alarm fallback)")
                    Thread { runUsageScan(2) }.start()
                }
            }
            handler.postDelayed(this, 6 * 60 * 60_000L)
        }
    }

    // ── Loop 4: Weekly insight notification ───────────────────────────────────
    // Analyses 7 days of usage and sends one surprising personal insight.
    // Only fires if InsightEngine.isDue() (6+ days since last insight).
    private val insightRunnable = object : Runnable {
        override fun run() {
            if (engineReady && insightEngine.isDue()) {
                Thread {
                    try {
                        val report = insightEngine.generate()
                        if (report != null) {
                            postInsightNotif(report)
                            insightEngine.markSent(report.narrative)
                            android.util.Log.i(TAG, "Insight sent: ${report.insightType}")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Insight error: ${e.message}")
                    }
                }.start()
            }
            // Check daily — actual delivery gated by InsightEngine.isDue()
            handler.postDelayed(this, 24 * 60 * 60_000L)
        }
    }

    // ── Loop 5: Phone mode evaluation — every 5 minutes ───────────────────────
    // Silently switches phone mode (sleep, commute, work) based on sensor context.
    // Min 30 min between switches (PhoneModeManager.canSwitch()).
    private val modeEvalRunnable = object : Runnable {
        override fun run() {
            try {
                val decision = phoneModeManager.evaluate()
                if (decision != null) {
                    android.util.Log.i(TAG, "Mode switched: ${decision.mode} — ${decision.reason}")
                    postModeNotif(decision)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Mode eval error: ${e.message}")
            }
            handler.postDelayed(this, 5 * 60_000L)
        }
    }

    private fun runUsageScan(days: Int) {
        try {
            val result  = UsageAnalyser.analyse(this, days)
            val pending = File(filesDir, NotifTapReceiver.PENDING_FB_FILE)
            // feedIntoAxiom removed — adapter trains only from real accept/reject feedback
            if (engineReady && pending.exists()) {
                val n = AxiomEngine.replayPendingFeedback(pending.absolutePath)
                if (n > 0) pending.delete()
            }
            getSharedPreferences(ChargingReceiver.PREFS_NAME, MODE_PRIVATE).edit()
                .putLong(ChargingReceiver.KEY_LAST_USAGE_TS,
                    System.currentTimeMillis() / 1000L)
                .apply()
            android.util.Log.i(TAG, "Usage scan done: ${result.mappedEvents} events")
            updateServiceNotif()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Usage scan error: ${e.message}")
        }
    }

    // ── Public API — called by MainActivity via LocalBinder ───────────────────

    /** Run inference on user text. Returns JSON. */
    /**
     * Three-tier inference with real LLM chat for non-phone inputs.
     *
     *   Tier 1 — Keyword bypass (0ms):
     *     High-confidence phone action → instant. "Turn on wifi", "alarm", etc.
     *
     *   Tier 2 — LLM intent classification for ambiguous phone inputs (5-20s):
     *     If the input has phone vocabulary but low keyword confidence, a tiny
     *     few-shot prompt (~50 tokens) asks the LLM for one intent token.
     *
     *   Tier 3 — LLM CHAT for everything else (1-5s with ARM compiler flags):
     *     "Who are you", "thanks", "tell me a joke", "what's the weather" — all
     *     get a genuine LLM reply. The LLM uses a short system prompt (~30 tokens)
     *     and the user's message, nothing more. This is the chat-like behaviour.
     *
     * SPEED NOTE: The previous 3-minute latency was caused by missing ARM compiler
     * flags in CMakeLists.txt (-O3 -march=armv8.2-a+dotprod+fp16). With those flags,
     * the LLM runs at 30-60 tokens/sec. A typical reply = 1-3 seconds.
     */
    fun infer(prompt: String): String {
        if (!engineReady) return buildReply("Engine is still loading, please wait.")
        val t0 = System.currentTimeMillis()

        // ── Tier 0: direct app launch ─────────────────────────────────────────
        // Check if the user is asking to open a specific app by name.
        // Pure Kotlin lookup — zero latency, zero LLM.
        // Handles: "open Spotify", "Spotify", "launch YouTube", "WhatsApp" etc.
        //
        // IMPORTANT: in-app search (Tier 0.7) must be checked FIRST when the
        // prompt contains a search verb.  Without this guard, "search spotify for
        // Blinding Lights" is caught here as a plain app launch and Spotify opens
        // on its home screen instead of the search results.
        val inAppSearchVerbs = listOf(
            "find", "search", "look for", "play", "watch", "listen to",
            "show me", "search for", "look up", "put on",
            "dhundho", "search karo", "play karo", "chalao"
        )
        val promptLower = prompt.lowercase()
        val hasSearchVerb = inAppSearchVerbs.any { promptLower.contains(it) }

        if (!hasSearchVerb) {
            // No search verb → safe to try plain app launch first
            val appIntent = tryLaunchApp(prompt)
            if (appIntent != null) {
                android.util.Log.d(TAG, "T0 app launch for: '${prompt.take(30)}'")
                return appIntent
            }
        }

        // ── Tier 0.3: structured app actions (call/msg/post on app) ─────────────────────
        val appActionReply = detectAppAction(prompt)
        if (appActionReply != null) return appActionReply


        // ── Tier 0.5: file search routing ────────────────────────────────────
        val fileSearchReply = detectFileSearchQuery(prompt)
        if (fileSearchReply != null) return fileSearchReply

        // ── Tier 0.6: AI detector routing ────────────────────────────────────
        // e.g. "ai detector", "check if this is ai", "is this ai generated"
        val aiDetectorReply = detectAiDetectorQuery(prompt)
        if (aiDetectorReply != null) return aiDetectorReply

        // ── Tier 0.7: smart actions (time/date, alarm, reminder, email, in-app search) ─
        val smartReply = detectSmartAction(prompt)
        if (smartReply != null) return smartReply

        // ── Tier 0 (fallback): app launch when a search verb was present but ──
        // Tier 0.7 didn't match any known app target (e.g. "search my phone").
        // This ensures "play Candy Crush" still opens the game even though
        // "play" is a search verb but Candy Crush isn't in our in-app targets.
        if (hasSearchVerb) {
            val appIntent = tryLaunchApp(prompt)
            if (appIntent != null) {
                android.util.Log.d(TAG, "T0 fallback app launch: '${prompt.take(30)}'")
                return appIntent
            }
        }

        // ── Tier 1: keyword + adapter pipeline ───────────────────────────────
        val keywordRaw  = runCatching { AxiomEngine.inferIntent(prompt) }.getOrElse {
            android.util.Log.e(TAG, "inferIntent error: ${it.message}")
            return buildReply("Something went wrong, please try again.")
        }
        val keywordJson = runCatching { org.json.JSONObject(keywordRaw) }.getOrNull()
        val keywordObj  = keywordJson?.optString("object", "NONE") ?: "NONE"
        val keywordConf = keywordJson?.optDouble("confidence", 0.0)?.toFloat() ?: 0f
        val isBypass    = keywordJson?.optBoolean("keyword_bypass", false) ?: false

        // C++ set keyword_bypass=true → it already decided to skip the LLM.
        // Trust it unconditionally — do NOT re-check confidence here.
        // Sensor modulation runs AFTER the C++ bypass point and lowers the
        // returned conf (e.g. 1.00 → 0.85), so a keywordConf >= 0.88 gate
        // here defeats every bypass silently.
        // Also: return before Tier 2 even if looksLikePhoneRequest() is true —
        // "battery", "display", "wifi" are all in PHONE_VOCAB and would re-trigger
        // the LLM on every bypassed request without this guard.
        if (isBypass && keywordObj != "NONE") {
            android.util.Log.d(TAG, "T1 bypass ${System.currentTimeMillis()-t0}ms: $keywordObj conf=$keywordConf")
            return keywordRaw
        }

        // ── Tier 2: LLM classification for ambiguous phone requests ──────────
        if (keywordConf >= 0.40f || looksLikePhoneRequest(prompt)) {
            val llmRaw = runCatching {
                AxiomEngine.classifyIntent(buildClassifyPrompt(prompt))
            }.getOrNull()?.trim()?.uppercase() ?: ""

            android.util.Log.d(TAG, "T2 classify ${System.currentTimeMillis()-t0}ms: '$llmRaw'")

            if (llmRaw.isNotEmpty() && llmRaw != "NONE" && llmRaw.length < 60) {
                val intentName = if (llmRaw.startsWith("ACTION_")) llmRaw else "ACTION_$llmRaw"
                val pipelineResult = runCatching { AxiomEngine.inferIntent(intentName) }.getOrNull()
                val pipelineObj = runCatching {
                    org.json.JSONObject(pipelineResult ?: "").optString("object", "NONE")
                }.getOrDefault("NONE")
                if (pipelineObj != "NONE") {
                    android.util.Log.i(TAG, "T2 intent: $pipelineObj")
                    return pipelineResult!!
                }
            }

            if (keywordObj != "NONE" && keywordConf >= 0.40f) return keywordRaw
        }

        // ── Tier 2.5: Identity / creator questions — hardcoded, no LLM ────────
        // The LLM will always say "Google" or "Anthropic" — intercept these here.
        val identityReply = checkIdentityQuestion(prompt)
        if (identityReply != null) {
            android.util.Log.d(TAG, "Identity reply for: '${prompt.take(40)}'")
            return buildReply(identityReply)
        }

        // ── Tier 3: LLM chat reply ────────────────────────────────────────────
        android.util.Log.d(TAG, "T3 chat: '${prompt.take(40)}'")
        val chatReply = runCatching {
            AxiomEngine.conversationalAnswer(prompt, 120)
        }.getOrElse {
            android.util.Log.e(TAG, "Chat error: ${it.message}")
            ""
        }.trim()

        android.util.Log.d(TAG, "T3 ${System.currentTimeMillis()-t0}ms: '${chatReply.take(60)}'")

        // Guard: if LLM outputs a single word or looks like an intent token, discard it
        val isGibberish = chatReply.isEmpty() ||
                chatReply.all { it.isUpperCase() || it == '_' } ||
                (!chatReply.contains(' ') && chatReply.length < 15)

        return if (!isGibberish) buildReply(chatReply)
        else buildReply("I'm not sure how to help with that. You can try asking about phone settings or apps, or I can search the web for you.")
    }

    /**
     * Tier 0: check if the input is asking to open an app.
     * Returns a JSON string with object=APP_LAUNCH if matched, null otherwise.
     *
     * Matching: strip "open"/"launch"/"start"/"run" prefix, then match the
     * remaining word(s) against a map of popular app names → package names.
     * Also does a fuzzy installed-app search for anything not in the map.
     */
    private fun tryLaunchApp(input: String): String? {
        return runCatching { tryLaunchAppInternal(input) }.getOrElse {
            android.util.Log.e(TAG, "tryLaunchApp crashed: ${it.message}")
            null
        }
    }

    private fun tryLaunchAppInternal(input: String): String? {
        val s = input.trim().lowercase()
            .removePrefix("open ").removePrefix("launch ")
            .removePrefix("start ").removePrefix("run ")
            .removePrefix("show ").removePrefix("go to ")
            .trim()

        if (s.length < 2) return null

        val pm = applicationContext.packageManager

        // Known apps: name → candidate packages (tried in order, first installed wins)
        val knownApps = mapOf(
            "spotify"       to listOf("com.spotify.music"),
            "youtube"       to listOf("com.google.android.youtube"),
            "youtube music" to listOf("com.google.android.apps.youtube.music"),
            "netflix"       to listOf("com.netflix.mediaclient"),
            "prime video"   to listOf("com.amazon.avod.thirdpartyclient"),
            "hotstar"       to listOf("in.startv.hotstar"),
            "disney+"       to listOf("com.disney.disneyplus"),
            "soundcloud"    to listOf("com.soundcloud.android"),
            "gaana"         to listOf("com.gaana"),
            "whatsapp"      to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "instagram"     to listOf("com.instagram.android"),
            "facebook"      to listOf("com.facebook.katana"),
            "messenger"     to listOf("com.facebook.orca"),
            "telegram"      to listOf("org.telegram.messenger"),
            "snapchat"      to listOf("com.snapchat.android"),
            "twitter"       to listOf("com.twitter.android"),
            "tiktok"        to listOf("com.zhiliaoapp.musically"),
            "linkedin"      to listOf("com.linkedin.android"),
            "discord"       to listOf("com.discord"),
            "signal"        to listOf("org.thoughtcrime.securesms"),
            "gmail"         to listOf("com.google.android.gm"),
            "maps"          to listOf("com.google.android.apps.maps"),
            "google maps"   to listOf("com.google.android.apps.maps"),
            "chrome"        to listOf("com.android.chrome"),
            "drive"         to listOf("com.google.android.apps.docs"),
            "meet"          to listOf("com.google.android.apps.meetings"),
            "calendar"      to listOf("com.google.android.calendar","com.samsung.android.calendar"),
            "keep"          to listOf("com.google.android.keep"),
            "photos"        to listOf("com.google.android.apps.photos","com.miui.gallery","com.sec.android.gallery3d"),
            "zoom"          to listOf("us.zoom.videomeetings"),
            "teams"         to listOf("com.microsoft.teams"),
            "outlook"       to listOf("com.microsoft.office.outlook"),
            "notion"        to listOf("notion.id"),
            "slack"         to listOf("com.Slack"),
            "amazon"        to listOf("com.amazon.mShop.android.shopping"),
            "flipkart"      to listOf("com.flipkart.android"),
            "paytm"         to listOf("net.one97.paytm"),
            "phonepe"       to listOf("com.phonepe.app"),
            "gpay"          to listOf("com.google.android.apps.nbu.paisa.user"),
            "google pay"    to listOf("com.google.android.apps.nbu.paisa.user"),
            "play store"    to listOf("com.android.vending"),
            "playstore"     to listOf("com.android.vending"),
            "calculator"    to listOf("com.google.android.calculator","com.android.calculator2","com.miui.calculator"),
            "camera"        to listOf("com.google.android.GoogleCamera","com.miui.camera","com.sec.android.app.camera","com.oneplus.camera"),
            "gallery"       to listOf("com.google.android.apps.photos","com.miui.gallery","com.sec.android.gallery3d"),
            "contacts"      to listOf("com.google.android.contacts","com.samsung.android.contacts","com.miui.contacts"),
            "phone"         to listOf("com.google.android.dialer","com.samsung.android.dialer","com.miui.phone"),
            "dialer"        to listOf("com.google.android.dialer","com.samsung.android.dialer"),
            "messages"      to listOf("com.google.android.apps.messaging","com.samsung.android.messaging"),
            "sms"           to listOf("com.google.android.apps.messaging","com.samsung.android.messaging"),
            "files"         to listOf("com.google.android.apps.nbu.files","com.miui.fileexplorer","com.sec.android.app.myfiles"),
            "clock"         to listOf("com.google.android.deskclock","com.android.deskclock","com.sec.android.app.clockpackage","com.miui.clock","com.oneplus.clock","com.coloros.alarmclock","com.realme.clock","com.vivo.alarmclock","com.huawei.deskclock","com.asus.deskclock"),
            "settings"      to listOf("com.android.settings")
        )

        // Step 1: match against known apps list
        val matchedKey = knownApps.keys.firstOrNull { it == s }
            ?: knownApps.keys.firstOrNull { s == it }
            ?: knownApps.keys.firstOrNull { s.contains(it) && it.length >= 4 }
            ?: knownApps.keys.firstOrNull { it.contains(s) && s.length >= 4 }

        if (matchedKey != null) {
            for (pkg in knownApps[matchedKey]!!) {
                // Check installed via getApplicationInfo — works on all ROMs including MIUI.
                // getLaunchIntentForPackage returns null on MIUI for third-party apps (Android 11+).
                val installed = runCatching {
                    pm.getApplicationInfo(pkg, 0); true
                }.getOrDefault(false)
                if (installed) {
                    android.util.Log.i(TAG, "T0 known: '$s' → $pkg")
                    return buildAppLaunch(pkg, matchedKey)
                }
            }
            // Packages not installed — fall through to fuzzy scan
            android.util.Log.d(TAG, "T0 known key '$matchedKey' found but not installed")
        }

        // Step 2: scan ALL installed apps by visible label.
        // getLaunchIntentForPackage() returns null on MIUI/Android 11+ for third-party
        // apps due to package visibility rules — so we cannot use it to check launchability.
        //
        // Instead: getInstalledApplications(0) always returns all packages on all ROMs.
        // To check if an app is user-launchable we use getPackageInfo with GET_ACTIVITIES
        // and look for an activity with ACTION_MAIN + CATEGORY_LAUNCHER intent filter.
        // This is the same data the home screen uses — no permission required.
        if (s.length >= 3) {
            val allApps = runCatching {
                pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            }.getOrNull()
            // GET_META_DATA can OOM on some devices — fall back to flag 0
                ?: runCatching { pm.getInstalledApplications(0) }.getOrNull()
                ?: emptyList()

            var exactMatch: Pair<String,String>? = null   // pkg to label
            var partialMatch: Pair<String,String>? = null

            for (appInfo in allApps) {
                val pkg   = appInfo.packageName
                val label = runCatching {
                    pm.getApplicationLabel(appInfo).toString().lowercase()
                }.getOrNull() ?: continue

                when {
                    label == s          -> { exactMatch = pkg to label; break }
                    label.contains(s) && partialMatch == null -> partialMatch = pkg to label
                    s.length >= 4 && label.contains(s.take(s.length - 1)) && partialMatch == null ->
                        partialMatch = pkg to label
                }
            }

            val match = exactMatch ?: partialMatch
            if (match != null) {
                val (pkg, label) = match
                // Accept the match — user-installed apps are always launchable.
                // System apps in the match are also fine since they matched by label.
                // MainActivity's launch fallback chain handles the actual opening.
                android.util.Log.i(TAG, "T0 scan: '$s' -> $pkg ('$label')")
                return buildAppLaunch(pkg, label)
            }
        }

        return null
    }

    // ═══════════════════════════════════════════════════════
    //  Tier 0.3 helpers
    // ═══════════════════════════════════════════════════════
    private val callVerbs    = listOf("call", "ring", "dial", "phone",
        "audio call", "voice call", "call karo", "phone karo")
    private val videoVerbs   = listOf("video call", "video chat", "facetime", "video karo")
    private val messageVerbs = listOf("message", "text", "msg", "dm",
        "chat", "ping", "send message", "message karo", "msg karo")
    private val postVerbs    = listOf("post", "tweet", "share")
    private val cabVerbs     = listOf("book cab", "book a cab", "book ride",
        "get cab", "get uber", "book ola", "book uber", "cab book karo")

    private fun detectAppAction(prompt: String): String? {
        val q = prompt.trim().lowercase()
        val appTarget = extractAppTarget(q) ?: return null
        val profile   = AppIntentRegistry.findApp(appTarget) ?: return null
        if (cabVerbs.any { q.contains(it) }) {
            val dest = extractContactOrQuery(q, cabVerbs + listOf("to","book","get","call"))
            val intent = AppIntentRegistry.buildNavigateIntent(this, profile, dest) ?: return null
            return try { startActivity(intent)
                buildReply("Booking ${profile.displayName}${if (dest.isNotBlank()) " to $dest" else ""}.")
            } catch (e: Exception) { null }
        }
        if (postVerbs.any { q.startsWith(it) } && profile.postUri != null) {
            val text = extractContactOrQuery(q, postVerbs)
            if (text.isNotBlank()) {
                val uri = profile.postUri.replace("{q}", java.net.URLEncoder.encode(text, "UTF-8"))
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    buildReply("Opening ${profile.displayName} to post.")
                } catch (e: Exception) { null }
            }
        }
        if (videoVerbs.any { q.startsWith(it) }) {
            val contact = resolveContact(extractContactOrQuery(q, videoVerbs))
            val intent  = AppIntentRegistry.buildCallIntent(this, profile, contact?.phone, isVideo = true) ?: return null
            return try { startActivity(intent)
                buildReply("Video calling${if (contact?.displayName?.isNotBlank() == true) " ${contact.displayName}" else ""} on ${profile.displayName}.")
            } catch (e: Exception) { null }
        }
        if (callVerbs.any { q.startsWith(it) }) {
            val contact = resolveContact(extractContactOrQuery(q, callVerbs))
            val intent  = AppIntentRegistry.buildCallIntent(this, profile, contact?.phone, isVideo = false) ?: return null
            return try { startActivity(intent)
                buildReply("Calling${if (contact?.displayName?.isNotBlank() == true) " ${contact.displayName}" else ""} on ${profile.displayName}.")
            } catch (e: Exception) { null }
        }
        if (messageVerbs.any { q.startsWith(it) }) {
            val contact = resolveContact(extractContactOrQuery(q, messageVerbs))
            val intent  = AppIntentRegistry.buildMessageIntent(this, profile, contact?.phone, "") ?: return null
            return try { startActivity(intent)
                buildReply("Opening ${profile.displayName}${if (contact?.displayName?.isNotBlank() == true) " chat with ${contact.displayName}" else ""}.")
            } catch (e: Exception) { null }
        }
        return null
    }

    private fun resolveContact(name: String): ContactResolver.ContactInfo? {
        if (name.isBlank()) return null
        if (name.all { it.isDigit() || it == '+' || it == '-' })
            return ContactResolver.ContactInfo(name, name, null)
        return runCatching { ContactResolver.resolve(name, this) }.getOrNull()
    }

    private fun extractContactOrQuery(q: String, verbs: List<String>): String {
        var s = q
        for (v in verbs.sortedByDescending { it.length }) {
            if (s.startsWith(v)) { s = s.removePrefix(v).trim(); break }
        }
        for (m in listOf(" on ", " in ", " via ", " using ", " pe ", " mein ")) {
            val idx = s.lastIndexOf(m); if (idx >= 0) { s = s.substring(0, idx).trim(); break }
        }
        for (f in listOf("a ", "an ", "the ", "my ")) { if (s.startsWith(f)) s = s.removePrefix(f).trim() }
        return s
    }


    /**
     * Tier 0.5: detect natural-language file search queries and route to FileSearchActivity.
     * Returns buildReply() JSON if routed, null if not a file search query.
     */
    private fun detectFileSearchQuery(prompt: String): String? {
        val q = prompt.lowercase()

        val searchTriggers  = listOf("show", "find", "search", "look for", "where is",
            "where are", "get me", "display")
        val fileContextWords = listOf("file", "image", "photo", "picture", "video",
            "pdf", "document", "doc", "whatsapp", "telegram", "download",
            "screenshot", "gallery", "camera", "media", "received", "sent")
        val timeContextWords = listOf("yesterday", "last week", "this week", "today",
            "monday","tuesday","wednesday","thursday","friday","saturday","sunday",
            "last month","this month","days ago","weeks ago")

        val hasTrigger = searchTriggers.any  { q.contains(it) }
        val hasFile    = fileContextWords.any { q.contains(it) }
        val hasTime    = timeContextWords.any { q.contains(it) }

        if (!((hasTrigger && hasFile) || (hasFile && hasTime))) return null

        android.util.Log.i(TAG, "Tier 0.5 file search: '${prompt.take(60)}'")
        runCatching {
            val intent = Intent(this, FileSearchActivity::class.java).apply {
                putExtra(FileSearchActivity.EXTRA_INITIAL_QUERY, prompt)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }.onFailure {
            android.util.Log.e(TAG, "FileSearchActivity launch failed: ${it.message}")
        }
        return buildReply("Opening file search for: \"${prompt.take(50)}\"")
    }

    /**
     * Tier 0.6: detect AI content detection requests and route to AiDetectorActivity.
     */
    private fun detectAiDetectorQuery(prompt: String): String? {
        val q = prompt.lowercase()

        // Image-specific triggers → open on Image tab (tab = 1)
        val imageTriggersExact = listOf(
            "ai image detector", "check ai image", "detect ai image",
            "is this image ai", "is this photo ai", "is this picture ai",
            "ai photo detector", "ai photo check", "fake image detector",
            "check image ai", "image ai checker", "photo ai checker",
            "deepfake", "detect fake image", "fake photo", "fake picture",
            "is this ai generated image", "ai generated photo", "ai generated image",
            "midjourney detector", "dalle detector", "stable diffusion detector"
        )

        // Generic / text triggers → open on Text tab (tab = 0)
        val textTriggers = listOf(
            "ai detector", "ai detection", "detect ai", "check if ai", "is this ai",
            "ai content", "check ai", "ai checker", "is it ai",
            "was this written by ai", "is this generated", "check for ai",
            "ai or human", "human or ai", "ai text checker", "check this text"
        )

        val openImageTab = imageTriggersExact.any { q.contains(it) }
        val openTextTab  = !openImageTab && textTriggers.any { q.contains(it) }

        if (!openImageTab && !openTextTab) return null

        val tabIndex = if (openImageTab) 1 else 0
        val replyMsg = if (openImageTab) "Opening AI Image Detector." else "Opening AI Content Detector."

        android.util.Log.i(TAG, "Tier 0.6 AI detector (tab=$tabIndex): '${prompt.take(50)}'")
        runCatching {
            startActivity(
                Intent(this, AiDetectorActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(AiDetectorActivity.EXTRA_OPEN_TAB, tabIndex)
            )
        }.onFailure {
            android.util.Log.e(TAG, "AiDetectorActivity launch failed: ${it.message}")
        }
        return buildReply(replyMsg)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Tier 0.7 — Smart Actions
    //  time/date, alarm, reminder, email, in-app search
    //  Returns buildReply() JSON or null to fall through to Tier 1+
    // ════════════════════════════════════════════════════════════════════════

    private fun detectSmartAction(prompt: String): String? {
        val q = prompt.lowercase().trim()
        return detectTimeQuery(q, prompt)
            ?: detectAlarmRequest(q, prompt)
            ?: detectReminderRequest(q, prompt)
            ?: detectEmailRequest(q, prompt)
            ?: detectMapsRequest(q, prompt)
            ?: detectInAppSearch(q, prompt)
    }

    // ── Time / Date ───────────────────────────────────────────────────────────
    private fun detectTimeQuery(q: String, original: String): String? {
        val timeTriggers = listOf(
            "what time", "current time", "what's the time", "whats the time",
            "tell me the time", "time now", "time is it", "time please",
            "time right now", "abhi time", "time batao", "time kya hai"
        )
        val dateTriggers = listOf(
            "what date", "what day", "today's date", "todays date", "current date",
            "what is today", "which day", "what day is it", "date today",
            "date kya hai", "aaj kya date", "aaj kaun sa din"
        )
        val cal    = Calendar.getInstance()
        val isTime = timeTriggers.any { q.contains(it) }
        val isDate = dateTriggers.any { q.contains(it) }
        val isTimeAlone = !isDate && !q.contains("alarm") && !q.contains("remind") &&
                q.length < 20 && q.contains("time")
        val isDateAlone = !isTime && (q == "date" || q == "today" ||
                (q.contains("today") && q.length < 15))

        if (!isTime && !isDate && !isTimeAlone && !isDateAlone) return null

        val reply: String
        if (isDate || isDateAlone) {
            val dayFmt  = SimpleDateFormat("EEEE", Locale.getDefault())
            val dateFmt = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
            reply = "Today is ${dayFmt.format(cal.time)}, ${dateFmt.format(cal.time)}."
        } else {
            val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
            reply = "The current time is ${timeFmt.format(cal.time)}."
        }
        android.util.Log.i(TAG, "Tier 0.7 time/date reply")
        return buildReply(reply)
    }

    // ── Alarm ─────────────────────────────────────────────────────────────────
    private fun detectAlarmRequest(q: String, original: String): String? {
        val alarmVerbs = listOf(
            "set alarm", "set an alarm", "alarm for", "alarm at",
            "wake me", "wake me up", "wake me at", "wake me by",
            "alarm karo", "alarm lagao", "alarm set karo",
            "remind me to wake", "morning alarm"
        )
        if (alarmVerbs.none { q.contains(it) }) return null

        val time  = extractTime(q)
        val label = extractAlarmLabel(q)

        if (time == null) {
            runCatching {
                startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            return buildReply("Opening your alarms. Please set the time manually.")
        }

        val hour   = time.first
        val minute = time.second
        val amPm   = if (hour < 12) "AM" else "PM"
        val h12    = if (hour % 12 == 0) 12 else hour % 12
        val minStr = minute.toString().padStart(2, '0')
        val timeStr = if (minute == 0) "$h12 $amPm" else "$h12:$minStr $amPm"

        runCatching {
            startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label ?: "Axiom Alarm")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure {
            android.util.Log.e(TAG, "Alarm intent failed: ${it.message}")
        }

        android.util.Log.i(TAG, "Tier 0.7 alarm: $hour:$minute label=$label")
        val labelSuffix = if (label != null) " for $label" else ""
        return buildReply("Alarm set for $timeStr$labelSuffix.")
    }

    // ── Reminder ──────────────────────────────────────────────────────────────
    private fun detectReminderRequest(q: String, original: String): String? {
        val reminderVerbs = listOf(
            "remind me", "set a reminder", "set reminder", "reminder for",
            "reminder at", "don't let me forget", "remind me to",
            "reminder to", "mujhe remind karo", "reminder lagao",
            "add reminder", "create reminder"
        )
        if (reminderVerbs.none { q.contains(it) }) return null

        val time  = extractTime(q)
        val topic = extractReminderTopic(q, original)
        val label = if (topic != null && topic.isNotBlank()) topic else "Reminder"

        val hour:   Int
        val minute: Int
        if (time != null) {
            hour   = time.first
            minute = time.second
        } else {
            hour   = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            minute = 0
        }
        val amPm   = if (hour < 12) "AM" else "PM"
        val h12    = if (hour % 12 == 0) 12 else hour % 12
        val minStr = minute.toString().padStart(2, '0')
        val timeStr = if (minute == 0) "$h12 $amPm" else "$h12:$minStr $amPm"

        runCatching {
            startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure {
            android.util.Log.e(TAG, "Reminder intent failed: ${it.message}")
        }

        android.util.Log.i(TAG, "Tier 0.7 reminder: $label at $hour:$minute")
        val timeNote = if (time != null) " for $timeStr" else ""
        return buildReply("Reminder set$timeNote: $label.")
    }

    // ── Email ─────────────────────────────────────────────────────────────────
    private fun detectEmailRequest(q: String, original: String): String? {
        val emailVerbs = listOf(
            "send email", "send an email", "send mail", "write email",
            "compose email", "email to", "send a mail", "draft email",
            "new email", "send message to", "mail karo", "email bhejo",
            "email likho", "send e-mail", "compose a mail", "write a mail"
        )
        if (emailVerbs.none { q.contains(it) }) return null

        val to      = extractEmailAddress(original)
        val subject = extractEmailSubject(original)
        val body    = extractEmailBody(original)

        runCatching {
            val emailIntent = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                if (to != null)      putExtra(Intent.EXTRA_EMAIL,   arrayOf(to))
                if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
                if (body != null)    putExtra(Intent.EXTRA_TEXT,    body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(emailIntent, "Send email via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure {
            android.util.Log.e(TAG, "Email intent failed: ${it.message}")
        }

        android.util.Log.i(TAG, "Tier 0.7 email: to=$to subject=$subject")
        val toStr = if (to != null) " to $to" else ""
        return buildReply("Opening email composer$toStr.")
    }

    // ── Maps: navigate, directions, traffic, nearby, share ETA ──────────────
    private fun detectMapsRequest(q: String, original: String): String? {

        // ── Verb groups ───────────────────────────────────────────────────────
        val navVerbs = listOf(
            "navigate to", "navigate me to", "navigation to", "take me to", "go to", "get me to",
            "directions to", "direction to", "how to reach", "how do i get to",
            "how do i reach", "route to", "rasta batao", "le chalo", "jana hai",
            "chalana hai", "path to", "way to"
        )
        val trafficVerbs = listOf(
            "traffic", "traffic on", "traffic near", "how is traffic",
            "is there traffic", "road condition", "jam", "check traffic",
            "traffic status", "traffic update", "traffic batao", "jam hai"
        )
        val nearbyVerbs = listOf(
            "near me", "nearby", "closest", "nearest", "find a", "find nearby",
            "where is the nearest", "where is a", "aas paas", "paas mein",
            "near here", "close to me", "around me", "around here"
        )
        val etaVerbs = listOf(
            "how far is", "how long to", "how long will it take", "time to reach",
            "distance to", "eta to", "kitni door", "kitna time", "kitna waqt"
        )
        val searchMapsVerbs = listOf(
            "search on maps", "find on maps", "show on map", "maps mein", "map pe",
            "open maps", "show me on maps", "where is", "location of", "locate"
        )

        val isNav     = navVerbs.any     { q.contains(it) }
        val isTraffic = trafficVerbs.any { q.contains(it) }
        val isNearby  = nearbyVerbs.any  { q.contains(it) }
        val isEta     = etaVerbs.any     { q.contains(it) }
        val isMapsSearch = searchMapsVerbs.any { q.contains(it) }

        if (!isNav && !isTraffic && !isNearby && !isEta && !isMapsSearch) return null

        val enc: (String) -> String = { java.net.URLEncoder.encode(it, "UTF-8") }
        val pkg  = "com.google.android.apps.maps"

        // ── Sub-handler: Traffic check ────────────────────────────────────────
        if (isTraffic && !isNav) {
            // If a place is mentioned after "traffic on/near/to", search that area.
            // Otherwise open Maps in traffic-layer mode on current location.
            val place = extractMapsDestination(q,
                trafficVerbs + listOf("traffic", "check", "status", "update", "batao", "hai"))
            val uri = if (!place.isNullOrBlank()) {
                "https://www.google.com/maps/search/${enc(place)}/@?layer=traffic"
            } else {
                "https://www.google.com/maps/@?layer=traffic"
            }
            launchMapsUri(uri, pkg)
            val reply = if (!place.isNullOrBlank()) "Checking traffic near $place."
            else "Opening Maps traffic view for your area."
            android.util.Log.i(TAG, "Tier 0.7 maps traffic: place=$place")
            return buildReply(reply)
        }

        // ── Sub-handler: Nearby search ────────────────────────────────────────
        if (isNearby && !isNav) {
            val thing = extractMapsDestination(q,
                nearbyVerbs + listOf("find", "show", "where", "a", "the",
                    "nearest", "closest", "near", "me", "here", "around", "aas", "paas", "mein"))
            if (!thing.isNullOrBlank()) {
                val uri = "https://www.google.com/maps/search/${enc(thing + " near me")}"
                launchMapsUri(uri, pkg)
                android.util.Log.i(TAG, "Tier 0.7 maps nearby: $thing")
                return buildReply("Searching for $thing near you.")
            }
            // No specific thing — open Maps to let user search
            launchMapsUri("https://www.google.com/maps/", pkg)
            return buildReply("Opening Maps.")
        }

        // ── Sub-handler: ETA / distance ───────────────────────────────────────
        if (isEta && !isNav) {
            val dest = extractMapsDestination(q,
                etaVerbs + listOf("how", "far", "long", "take", "reach",
                    "distance", "eta", "kitni", "door", "kitna", "time", "waqt"))
            if (!dest.isNullOrBlank()) {
                val uri = "https://www.google.com/maps/dir/?api=1&destination=${enc(dest)}"
                launchMapsUri(uri, pkg)
                android.util.Log.i(TAG, "Tier 0.7 maps eta: $dest")
                return buildReply("Getting directions to $dest.")
            }
        }

        // ── Sub-handler: Generic Maps search ─────────────────────────────────
        if (isMapsSearch && !isNav) {
            val place = extractMapsDestination(q,
                searchMapsVerbs + listOf("search", "find", "show", "open",
                    "maps", "map", "where", "is", "on", "pe", "mein"))
            if (!place.isNullOrBlank()) {
                val uri = "https://www.google.com/maps/search/${enc(place)}"
                launchMapsUri(uri, pkg)
                return buildReply("Searching Maps for $place.")
            }
            launchMapsUri("https://www.google.com/maps/", pkg)
            return buildReply("Opening Maps.")
        }

        // ── Sub-handler: Navigation ───────────────────────────────────────────
        if (isNav) {
            val dest = extractMapsDestination(q,
                navVerbs + listOf("navigate", "navigation", "take", "me", "get",
                    "go", "directions", "direction", "route", "way", "path",
                    "how", "reach", "rasta", "batao", "chalo", "jana", "to"))
            if (!dest.isNullOrBlank()) {
                val uri = "https://www.google.com/maps/dir/?api=1" +
                        "&destination=${enc(dest)}&travelmode=driving"
                launchMapsUri(uri, pkg)
                android.util.Log.i(TAG, "Tier 0.7 maps nav: $dest")
                return buildReply("Starting navigation to $dest.")
            }
            // No destination parsed — open Maps and let user type
            launchMapsUri("https://www.google.com/maps/", pkg)
            return buildReply("Opening Maps — where do you want to go?")
        }

        return null
    }

    /** Opens a Maps URI in the Maps app; falls back to browser if not installed. */
    private fun launchMapsUri(uri: String, pkg: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).apply {
                `package` = pkg
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }.onFailure {
            // Maps not installed — open in browser
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }

    /**
     * Strips all verb/noise words from a Maps query to extract the destination.
     * "navigate to Connaught Place" → "Connaught Place"
     * "find hospitals near me"     → "hospitals"
     * "how is traffic on NH8"      → "NH8"
     */
    private fun extractMapsDestination(q: String, stripWords: List<String>): String? {
        var clean = q
        // Strip longest phrases first to avoid partial matches
        for (w in stripWords.sortedByDescending { it.length }) {
            clean = clean.replace(Regex("""\b${Regex.escape(w)}\b""", RegexOption.IGNORE_CASE), " ")
        }
        // Also strip common filler
        val filler = listOf("please", "can you", "could you", "will you",
            "a", "an", "the", "some", "me", "my", "i", "for",
            "ko", "ka", "ke", "ki", "se", "mujhe", "mera")
        for (w in filler) {
            clean = clean.replace(Regex("""\b${Regex.escape(w)}\b""", RegexOption.IGNORE_CASE), " ")
        }
        val result = clean.replace(Regex("""\s{2,}"""), " ").trim()
        return result.ifBlank { null }
    }

    // ── In-app search ─────────────────────────────────────────────────────────
    // ── Tier 0.7 in-app search — registry-backed ──────────────────────────────
    private fun detectInAppSearch(q: String, original: String): String? {
        val searchVerbs = listOf(
            "find", "search", "look for", "play", "watch", "listen to",
            "show me", "search for", "look up", "put on", "order", "buy",
            "dhundho", "search karo", "play karo", "chalao", "order karo"
        )
        if (searchVerbs.none { q.contains(it) }) return null
        val appTarget = extractAppTarget(q) ?: return null
        val profile   = AppIntentRegistry.findApp(appTarget) ?: return null
        val cleanQ    = extractInAppQuery(q, listOf(appTarget), searchVerbs)
        if (cleanQ.isBlank()) {
            runCatching {
                packageManager.getLaunchIntentForPackage(profile.pkg)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { startActivity(it) }
            }
            return buildReply("Opening ${profile.displayName}.")
        }
        val intent = when {
            q.contains("play") || q.contains("listen") || q.contains("chalao") ||
                    q.contains("put on") || q.contains("watch") ->
                AppIntentRegistry.buildPlayIntent(this, profile, cleanQ)
            else -> AppIntentRegistry.buildSearchIntent(this, profile, cleanQ)
        } ?: return null
        return try {
            startActivity(intent)
            android.util.Log.i(TAG, "Tier 0.7 in-app: ${profile.displayName} q=$cleanQ")
            buildReply("Searching ${profile.displayName} for \"$cleanQ\".")
        } catch (e: Exception) {
            runCatching {
                packageManager.getLaunchIntentForPackage(profile.pkg)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { startActivity(it) }
            }
            buildReply("Opening ${profile.displayName}.")
        }
    }

    private fun extractAppTarget(q: String): String? {
        for (marker in listOf(" on ", " in ", " via ", " using ", " pe ", " mein ")) {
            val idx = q.lastIndexOf(marker)
            if (idx >= 0) {
                val candidate = q.substring(idx + marker.length).trim()
                if (candidate.length >= 2) return candidate
            }
        }
        return null
    }


    // ── Extraction helpers ────────────────────────────────────────────────────

    private fun extractTime(q: String): Pair<Int, Int>? {
        if (q.contains("midnight"))  return Pair(0, 0)
        if (q.contains("noon") || q.contains("12pm") || q.contains("12 pm")) return Pair(12, 0)

        // HH:MM  e.g. "7:30 am"
        val colonRe = Regex("""(\d{1,2}):(\d{2})\s*(am|pm)?""")
        val cm = colonRe.find(q)
        if (cm != null) {
            var h   = cm.groupValues[1].toInt()
            val min = cm.groupValues[2].toInt()
            val sfx = cm.groupValues[3]
            if (sfx == "pm" && h < 12) h += 12
            if (sfx == "am" && h == 12) h = 0
            if (h in 0..23 && min in 0..59) return Pair(h, min)
        }

        // "half past 7" → 7:30  |  "quarter to 8" → 7:45  |  "quarter past 7" → 7:15
        val halfPastRe   = Regex("""half\s+past\s+(\d{1,2})""")
        val quarterToRe  = Regex("""quarter\s+to\s+(\d{1,2})""")
        val quarterPstRe = Regex("""quarter\s+past\s+(\d{1,2})""")
        val hp = halfPastRe.find(q)
        if (hp != null) return Pair(hp.groupValues[1].toInt(), 30)
        val qt = quarterToRe.find(q)
        if (qt != null) {
            val h = qt.groupValues[1].toInt()
            return Pair(if (h == 0) 23 else h - 1, 45)
        }
        val qp = quarterPstRe.find(q)
        if (qp != null) return Pair(qp.groupValues[1].toInt(), 15)

        // Plain "7 am", "7 30", "7 in the morning"
        val plainRe = Regex("""(\d{1,2})(?:\s+(\d{2}))?\s*(am|pm|morning|evening|night|tonight|afternoon)?""")
        val pm = plainRe.find(q)
        if (pm != null && pm.value.isNotBlank()) {
            var h   = pm.groupValues[1].toInt()
            val min = pm.groupValues[2].toIntOrNull() ?: 0
            val ctx = pm.groupValues[3]
            if (ctx == "pm" || ctx == "evening" || ctx == "night" ||
                ctx == "tonight" || ctx == "afternoon") {
                if (h < 12) h += 12
            } else if (ctx == "am" || ctx == "morning") {
                if (h == 12) h = 0
            } else {
                if (h in 1..6) h += 12
            }
            if (h in 0..23 && min in 0..59) return Pair(h, min)
        }
        return null
    }

    private fun extractAlarmLabel(q: String): String? {
        val re = Regex("""(?:alarm|wake me(?:\s+up)?)\s+(?:for|to)\s+(.+)""")
        return re.find(q)?.groupValues?.getOrNull(1)?.trim()?.take(50)
    }

    private fun extractReminderTopic(q: String, original: String): String? {
        val toRe  = Regex(
            """remind\s+me\s+(?:to\s+)?(.+?)(?:\s+at\s+|\s+by\s+|\s+tomorrow|\s+tonight|$)""",
            RegexOption.IGNORE_CASE)
        val forRe = Regex(
            """reminder\s+(?:for|to)\s+(.+?)(?:\s+at\s+|\s+by\s+|$)""",
            RegexOption.IGNORE_CASE)
        val raw = (toRe.find(original) ?: forRe.find(original))
            ?.groupValues?.getOrNull(1)?.trim() ?: return null
        return raw.take(80).ifBlank { null }
    }

    private fun extractEmailAddress(original: String): String? {
        val re = Regex("""[\w.+\-]+@[\w\-]+\.[a-zA-Z]{2,}""")
        return re.find(original)?.value
    }

    private fun extractEmailSubject(original: String): String? {
        val re = Regex("""subject\s*[:=]\s*(.+?)(?:\s+(?:saying|body)|$)""", RegexOption.IGNORE_CASE)
        return re.find(original)?.groupValues?.getOrNull(1)?.trim()?.take(100)
    }

    private fun extractEmailBody(original: String): String? {
        val re = Regex("""(?:saying|body|message)\s*[:=]\s*(.+)$""", RegexOption.IGNORE_CASE)
        return re.find(original)?.groupValues?.getOrNull(1)?.trim()?.take(500)
    }

    private fun extractInAppQuery(q: String, appKeywords: List<String>, verbs: List<String>): String {
        var clean = q
        for (kw in appKeywords.sortedByDescending { it.length }) {
            clean = clean.replace(kw, " ")
        }
        for (verb in verbs.sortedByDescending { it.length }) {
            clean = clean.replace(verb, " ")
        }
        val noise = listOf("on", "in", "at", "the", "a", "an", "for", "me",
            "please", "some", "video", "song", "music", "movie", "show",
            "series", "app", "pe", "ko", "se")
        for (word in noise) {
            clean = clean.replace(Regex("""\b${Regex.escape(word)}\b"""), " ")
        }
        return clean.replace(Regex("""\s{2,}"""), " ").trim()
    }

    /** JSON for an app launch — MainActivity reads the android_intent field to open it */
    private fun buildAppLaunch(packageName: String, label: String): String =
        """{"object":"APP_LAUNCH","confidence":1.0,"android_intent":"app:$packageName","app_label":${org.json.JSONObject.quote(label)}}"""

    private fun looksLikePhoneRequest(input: String): Boolean =
        PHONE_VOCAB.any { input.lowercase().contains(it) }

    private fun buildReply(text: String): String =
        """{"object":"NONE","confidence":0.0,"reply":${org.json.JSONObject.quote(text)}}"""

    private fun buildClassifyPrompt(userInput: String): String =
        "<|im_start|>system\nOutput one intent token only.\n<|im_end|>\n" +
                "<|im_start|>user\n" +
                "Intent: \"open wifi\" → ACTION_WIFI_SETTINGS\n" +
                "Intent: \"alarm\" → ACTION_ALARM\n" +
                "Intent: \"screen too dark\" → ACTION_BRIGHTNESS\n" +
                "Intent: \"$userInput\" →\n" +
                "<|im_end|>\n<|im_start|>assistant\n"

    /**
     * Intercepts identity/creator questions before they reach the LLM.
     * Returns a hardcoded reply string, or null if not an identity question.
     * Edit DEVELOPER_NAME and DEVELOPER_INFO to personalise.
     */
    private fun checkIdentityQuestion(input: String): String? {
        val s = input.trim().lowercase()

        // Patterns that mean "who made you / who are you"
        val identityPatterns = listOf(
            "who made you", "who created you", "who built you", "who developed you",
            "who is your creator", "who is your developer", "who is your maker",
            "who wrote you", "who designed you", "who programmed you",
            "who owns you", "who is your owner", "who is your author",
            "your creator", "your developer", "your maker", "your owner",
            "who are you", "what are you", "tell me about yourself",
            "introduce yourself", "what is axiom", "what is your name",
            "who made axiom", "who created axiom", "who built axiom"
        )

        if (identityPatterns.none { s.contains(it) }) return null

        // Personalise these:
        val developerName = "Rayad"
        val developerInfo = "from Germany" 

        return when {
            s.contains("who are you") || s.contains("what are you") ||
                    s.contains("introduce") || s.contains("about yourself") ||
                    s.contains("what is axiom") || s.contains("your name") ->
                "I'm Axiom, your on-device AI assistant. I can open apps, manage phone settings, " +
                        "and learn your habits to suggest things before you ask."

            s.contains("who made") || s.contains("who created") ||
                    s.contains("who built") || s.contains("who developed") ||
                    s.contains("who wrote") || s.contains("who designed") || s.contains("your developer") ||
                    s.contains("who programmed") ->
                "I was created by $developerName — $developerInfo. " +
                        "I run entirely on-device with no cloud dependency."

            s.contains("creator") || s.contains("developer") ||
                    s.contains("maker") || s.contains("owner") || s.contains("author") ->
                "My developer is $developerName. I'm Axiom — a private, on-device AI assistant."

            else ->
                "I'm Axiom, your on-device AI assistant, made by $developerName."
        }
    }

    /**
     * Record an app-open event so the proactive engine learns user habits.
     * Call this whenever the user opens an app through Axiom.
     * After 3+ opens at the same hour, Axiom will start suggesting it proactively.
     */
    fun recordAppOpen(packageName: String, appLabel: String) {
        if (!engineReady) return
        // Encode as "APP:com.spotify.music" so the pred table treats it
        // as a separate namespace from phone intent tokens.
        val intentKey = "APP:$packageName"
        AxiomEngine.registerFeedback(intentKey, 1)  // 1 = accepted
        android.util.Log.d(TAG, "App open recorded: $intentKey ($appLabel)")
    }

    fun feedback(intent: String, accepted: Boolean) {
        if (!engineReady) return
        AxiomEngine.registerFeedback(intent, if (accepted) 1 else 0)
        updateServiceNotif()
    }

    /** Adapter stats JSON string for EventViewerActivity. */
    fun adapterStats(): String =
        if (engineReady) AxiomEngine.getAdapterStats() else "{}"

    /** Replay a pending feedback file. Returns count replayed. */
    fun replayPending(path: String): Int =
        if (engineReady) AxiomEngine.replayPendingFeedback(path) else 0

    /** Whether engine is fully loaded and ready for inference. */
    fun isReady(): Boolean = engineReady

    /** Manual proactive check — used by "Check" button in MainActivity. */
    fun proactiveCheck(): String {
        if (!engineReady) return "{\"object\":\"NONE\",\"confidence\":0.0}"
        return AxiomEngine.proactiveSuggest()
    }

    // ── Loop 6: Life context — calendar reminders every 15 minutes ─────────
    // Reads CalendarContract, classifies events (flight/hotel/meeting/restaurant),
    // posts tiered notifications with action buttons (Book Cab, Airplane Mode, etc.)
    private val lifeContextRunnable = object : Runnable {
        override fun run() {
            if (lifeContextAssistant.hasCalendarPermission()) {
                Thread {
                    try {
                        val posted = lifeContextAssistant.check()
                        if (posted.isNotEmpty()) {
                            android.util.Log.i(TAG, "LifeContext: posted ${posted.size} reminder(s)")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "LifeContext error: ${e.message}")
                    }
                }.start()
            }
            handler.postDelayed(this, 15 * 60_000L)
        }
    }

    // ── Public API — new v0.4 features ───────────────────────────────────────

    /** Run battery diagnosis (blocking — call off main thread). */
    fun runBatteryDiagnosis(): BatteryDoctor.BatteryReport = batteryDoctor.diagnose()

    /** Get the last weekly insight text, or null if none yet. */
    fun getLastInsight(): String? = insightEngine.getLastInsight()

    /** Upcoming calendar events in next 24 h — used by InsightActivity. */
    fun getUpcomingEvents(): List<LifeContextAssistant.LifeEvent> =
        lifeContextAssistant.getUpcomingEvents()

    /** File search — blocking, call off main thread. */
    fun searchFiles(query: String): FileSearchEngine.SearchResult =
        fileSearchEngine.search(query)

    @Volatile private var dreamRunning = false

    /**
     * Start the dream learning cycle on a background thread.
     * Trigger: phone plugged in + battery >= 50%. No time restriction.
     *
     * Immediately writes "dreaming" to SharedPrefs and broadcasts
     * ACTION_DREAM_STARTED so MainActivity shows the animation even before
     * the learning completes. Shows an ongoing notification so the user
     * always sees something, even when the app is closed.
     */
    /**
     * Checks the 6-hour gap and calls startDreamCycle() if eligible.
     * Called directly from the sensor loop (already inside AxiomService)
     * and from onStartCommand (ACTION_START_DREAM from ChargingReceiver).
     *
     * NOTE: does NOT write KEY_LAST_DREAM_TS — that is done inside
     * startDreamCycle() AFTER all guards pass, so a failed attempt
     * (engine not ready) never blocks the next legitimate attempt.
     */
    private fun maybeTriggerDream() {
        val prefs       = getSharedPreferences(ChargingReceiver.PREFS_NAME, MODE_PRIVATE)
        val lastDreamMs = prefs.getLong(ChargingReceiver.KEY_LAST_DREAM_TS, 0L) * 1000L
        val nowMs       = System.currentTimeMillis()
        if (nowMs - lastDreamMs < ChargingReceiver.MIN_DREAM_GAP_MS) {
            android.util.Log.i(TAG, "Dream ran ${(nowMs - lastDreamMs) / 3600000}h ago — skipping")
            return
        }
        startDreamCycle()
    }

    /** Called by maybeTriggerDream() (sensor loop) and onStartCommand (POWER_CONNECTED). */
    fun startDreamCycle() {
        if (dreamRunning) { android.util.Log.i(TAG, "Dream already running"); return }
        android.util.Log.i(TAG, "startDreamCycle: engineReady=$engineReady dreamRunning=$dreamRunning")
        if (!engineReady) {
            // Engine still loading — schedule one retry in 90 seconds.
            // Do NOT set the last-dream timestamp yet; that would block retries.
            android.util.Log.i(TAG, "Engine not ready — will retry dream in 90s")
            handler.postDelayed({ maybeTriggerDream() }, 90_000L)
            return
        }
        dreamRunning = true

        // Lock the timestamp NOW — after all guard checks pass, not before.
        getSharedPreferences(ChargingReceiver.PREFS_NAME, MODE_PRIVATE).edit()
            .putLong(ChargingReceiver.KEY_LAST_DREAM_TS, System.currentTimeMillis() / 1000)
            .apply()

        getSharedPreferences(DreamService.PREFS_DREAM_UI, MODE_PRIVATE).edit()
            .putString(DreamService.KEY_DREAM_STATE, "dreaming")
            .putLong(DreamService.KEY_DREAM_TS, System.currentTimeMillis())
            .apply()

        sendBroadcast(Intent(DreamService.ACTION_DREAM_STARTED).apply { `package` = packageName })

        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(android.app.NotificationChannel(
                DreamService.NOTIF_CHANNEL, "Axiom Dreaming",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Shown while Axiom consolidates learning" })
        }
        nm.notify(DreamService.NOTIF_ID,
            androidx.core.app.NotificationCompat.Builder(this, DreamService.NOTIF_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Axiom is dreaming...")
                .setContentText("Consolidating what you taught me today")
                .setOngoing(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                .build()
        )
        android.util.Log.i(TAG, "Dream cycle started — notif posted, broadcast sent")

        Thread {
            try {
                val rawResult   = AxiomEngine.runDreamLearning()
                val json        = runCatching { org.json.JSONObject(rawResult) }.getOrNull()
                val trained     = json?.optInt("trained", 0) ?: 0
                val events      = json?.optInt("events",  0) ?: 0
                val adapterStr  = json?.optString("adapter", "{}") ?: "{}"
                val adapterJson = runCatching { org.json.JSONObject(adapterStr) }.getOrNull()
                val updates     = adapterJson?.optInt("total_updates", 0) ?: 0
                val topStr      = adapterJson?.optString("top_intents", "") ?: ""
                val top = topStr.split(",").take(3).joinToString(", ") { e ->
                    e.trim().removePrefix("ACTION_").replace("_", " ")
                        .lowercase().replaceFirstChar { ch -> ch.uppercase() }
                }
                val summary = listOf(
                    "Learned from $events interactions.",
                    "Adapter has $updates total training updates.",
                    if (top.isNotEmpty()) "Most trained: $top." else ""
                ).filter { it.isNotEmpty() }.joinToString("\n")

                val resultJson = org.json.JSONObject().apply {
                    put("dream_ts", System.currentTimeMillis())  // milliseconds — matches Date()
                    put("trained",  trained)
                    put("events",   events)
                    put("adapter",  adapterStr)
                }.toString()

                val nowMs = System.currentTimeMillis()
                getSharedPreferences(DreamService.PREFS_DREAM_UI, MODE_PRIVATE).edit()
                    .putString(DreamService.KEY_DREAM_STATE,       "complete")
                    .putString(DreamService.KEY_DREAM_RESULT_JSON, resultJson)
                    .putLong(DreamService.KEY_DREAM_TS,            nowMs)
                    .apply()
                getSharedPreferences("axiom_dream", MODE_PRIVATE).edit()
                    .putString("last_dream_summary", summary)
                    .putLong("last_dream_ts", nowMs)
                    .apply()

                nm.notify(DreamService.NOTIF_ID,
                    androidx.core.app.NotificationCompat.Builder(this, DreamService.NOTIF_CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("Axiom Dream Complete")
                        .setContentText(
                            if (trained > 0) "Learned from $events interactions."
                            else "Nothing new to learn yet — keep using Axiom!"
                        )
                        .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(summary))
                        .setAutoCancel(true)
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                        .build()
                )
                sendBroadcast(Intent(DreamService.ACTION_DREAM_COMPLETE).apply {
                    `package` = packageName
                    putExtra(DreamService.EXTRA_DREAM_RESULT, resultJson)
                })
                android.util.Log.i(TAG, "Dream complete: trained=$trained events=$events")

            } catch (e: Exception) {
                // Dream failed — write a visible error state so the user sees something
                // instead of the card silently disappearing. Common cause: no events yet
                // (first few days), or JNI crash. Both are safe to retry next charge cycle.
                android.util.Log.e(TAG, "Dream error: ${e.message}")
                val errorJson = org.json.JSONObject().apply {
                    put("dream_ts", System.currentTimeMillis())
                    put("trained",  0)
                    put("events",   0)
                    put("adapter",  "{}")
                    put("error",    e.message ?: "unknown error")
                }.toString()
                getSharedPreferences(DreamService.PREFS_DREAM_UI, MODE_PRIVATE).edit()
                    .putString(DreamService.KEY_DREAM_STATE,       "complete")
                    .putString(DreamService.KEY_DREAM_RESULT_JSON, errorJson)
                    .putLong(DreamService.KEY_DREAM_TS,            System.currentTimeMillis())
                    .apply()
                sendBroadcast(Intent(DreamService.ACTION_DREAM_COMPLETE).apply {
                    `package` = packageName
                    putExtra(DreamService.EXTRA_DREAM_RESULT, errorJson)
                })
                nm.notify(DreamService.NOTIF_ID,
                    androidx.core.app.NotificationCompat.Builder(this, DreamService.NOTIF_CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("Axiom Dream")
                        .setContentText("Nothing to learn yet — keep using Axiom for a few days.")
                        .setAutoCancel(true)
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                        .build()
                )
            } finally {
                dreamRunning = false
            }
        }.start()
    }

    /** Alias — keeps any existing callers compiling. */
    fun runDreamAndSurface() = startDreamCycle()


    fun getLastDreamSummary(): String? =
        getSharedPreferences("axiom_dream", MODE_PRIVATE).getString("last_dream_summary", null)

    /**
     * Clears the cached insight text so InsightActivity can request a fresh one.
     * Called by InsightActivity's "Refresh" button.
     */
    fun clearLastInsight() {
        getSharedPreferences(InsightEngine.PREFS_NAME, MODE_PRIVATE).edit()
            .remove(InsightEngine.KEY_LAST_TEXT)
            .remove(InsightEngine.KEY_LAST_INSIGHT)
            .apply()
    }


    /** Force-generate a new insight (for testing/manual trigger). */
    fun generateInsightNow(): InsightEngine.InsightReport? = insightEngine.generate()

    /** Get current phone mode (NORMAL, SLEEP, COMMUTE, WORK, etc.). */
    fun getCurrentPhoneMode(): String = phoneModeManager.getCurrentMode()

    /** Get phone mode switch log for display. */
    fun getModeSwitchLog(): List<String> = phoneModeManager.getModeLog()

    /** Reset phone to normal mode. */
    fun resetPhoneMode() { phoneModeManager.resetToNormal() }

    // ── Insight notification ─────────────────────────────────────────────────
    private fun postInsightNotif(report: InsightEngine.InsightReport) {
        val openApp = PendingIntent.getActivity(
            this, 500,
            Intent(this, InsightActivity::class.java).apply {
                putExtra("narrative", report.narrative)
                putExtra("data_points", report.dataPoints.toTypedArray())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_INSIGHT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Axiom noticed something")
            .setContentText(report.narrative.take(100))
            .setStyle(NotificationCompat.BigTextStyle().bigText(report.narrative))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_INSIGHT_ID, notif)
    }

    // ── Mode switch notification ──────────────────────────────────────────────
    private fun postModeNotif(decision: PhoneModeManager.ModeDecision) {
        val modeLabel = when (decision.mode) {
            PhoneModeManager.MODE_SLEEP   -> "🌙 Sleep mode"
            PhoneModeManager.MODE_COMMUTE -> "🎧 Commute mode"
            PhoneModeManager.MODE_WORK    -> "💼 Work mode"
            PhoneModeManager.MODE_FOCUS   -> "🎯 Focus mode"
            PhoneModeManager.MODE_GAMING  -> "🎮 Gaming mode"
            else                          -> "✅ Normal mode"
        }
        val openApp = PendingIntent.getActivity(
            this, 501,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_MODE)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(modeLabel)
            .setContentText(decision.reason)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_MODE_ID, notif)
    }

    // ── Proactive notification ────────────────────────────────────────────────
    private fun postProactiveNotif(action: String, score: Double) {
        val label     = friendlyLabel(action)
        val pct       = (score * 100).toInt()
        val androidAc = resolveAndroidAction(action)

        val tapIntent = Intent(this, NotifTapReceiver::class.java).apply {
            putExtra(NotifTapReceiver.EXTRA_ACTION,         action)
            putExtra(NotifTapReceiver.EXTRA_ANDROID_ACTION, androidAc)
            putExtra(NotifTapReceiver.EXTRA_ACCEPTED,       true)
        }
        val tapPi = PendingIntent.getBroadcast(
            this, action.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissIntent = Intent(this, NotifTapReceiver::class.java).apply {
            putExtra(NotifTapReceiver.EXTRA_ACTION,   action)
            putExtra(NotifTapReceiver.EXTRA_ACCEPTED, false)
        }
        val dismissPi = PendingIntent.getBroadcast(
            this, action.hashCode() + 10_000,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_PROACTIVE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Time to open $label?")
            .setContentText("You usually open $label around this time — $pct% match with your habits")
            .setContentIntent(tapPi)
            .setDeleteIntent(dismissPi)
            .addAction(0, "Not now", dismissPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_PROACTIVE_ID, notif)
    }

    // ── Service (persistent) notification ────────────────────────────────────
    private fun startForegroundCompat() {
        val notif = buildServiceNotif("Starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_SERVICE_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_SERVICE_ID, notif)
        }
    }

    fun updateServiceNotif() {
        try {
            val stats   = runCatching { JSONObject(adapterStats()) }.getOrNull()
            val updates = stats?.optInt("total_updates", 0) ?: 0
            val acc     = stats?.optDouble("accuracy", 0.0) ?: 0.0
            val text = when {
                !engineReady -> "Engine initialising…"
                updates == 0 -> "Learning your habits…"
                else         -> "Learned $updates events · ${(acc * 100).toInt()}% accuracy"
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_SERVICE_ID, buildServiceNotif(text))
        } catch (_: Exception) {}
    }

    private fun buildServiceNotif(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_BG)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Axiom")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)  // collapses in tray
            .build()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_BG, "Axiom Background",
                    NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Axiom runs in the background to learn your habits"
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROACTIVE, "Axiom Suggestions",
                    NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Proactive suggestions from Axiom"
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_INSIGHT, "Axiom Weekly Insights",
                    NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Weekly patterns and personal observations from Axiom"
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MODE, "Axiom Phone Mode",
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Automatic phone mode switches (sleep, commute, work)"
                    setShowBadge(false)
                }
            )
            // Life context notifications channel
            LifeContextAssistant.createChannel(this)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    fun friendlyLabel(action: String): String =
        action.removePrefix("ACTION_").replace("_", " ")
            .lowercase().replaceFirstChar { it.uppercase() }

    fun resolveAndroidAction(action: String): String = when (action) {
        "ACTION_BATTERY_SAVER",
        "ACTION_BATTERY_HEALTH",
        "ACTION_POWER_MENU",
        "ACTION_CHARGING_SETTINGS",
        "ACTION_ADAPTIVE_BATTERY"       -> "android.settings.BATTERY_SAVER_SETTINGS"
        "ACTION_BATTERY_USAGE"          -> "android.intent.action.POWER_USAGE_SUMMARY"
        "ACTION_BATTERY_OPTIMIZATION"   -> "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"
        "ACTION_BATTERY_PERCENTAGE"     -> "android.settings.DISPLAY_SETTINGS"
        "ACTION_MANAGE_STORAGE",
        "ACTION_CLEAR_CACHE",
        "ACTION_FILES"                  -> "android.settings.INTERNAL_STORAGE_SETTINGS"
        "ACTION_MANAGE_APPS",
        "ACTION_RUNNING_APPS",
        "ACTION_APP_INFO",
        "ACTION_KIDS_MODE"              -> "android.settings.APPLICATION_SETTINGS"
        "ACTION_SD_CARD"                -> "android.settings.MEMORY_CARD_SETTINGS"
        "ACTION_BACKUP",
        "ACTION_RESTORE"                -> "android.settings.PRIVACY_SETTINGS"
        "ACTION_INSTALL_APPS"           -> "android.settings.MANAGE_UNKNOWN_APP_SOURCES"
        "ACTION_DISPLAY_SETTINGS",
        "ACTION_BRIGHTNESS",
        "ACTION_DARK_MODE",
        "ACTION_SCREEN_TIMEOUT",
        "ACTION_FONT_SIZE",
        "ACTION_SCREEN_RESOLUTION",
        "ACTION_REFRESH_RATE",
        "ACTION_ALWAYS_ON_DISPLAY",
        "ACTION_BLUE_LIGHT_FILTER",
        "ACTION_SCREENSHOT",
        "ACTION_SCREEN_RECORDER",
        "ACTION_FLASHLIGHT",
        "ACTION_QUICK_SETTINGS",
        "ACTION_SPLIT_SCREEN",
        "ACTION_GAME_MODE"              -> "android.settings.DISPLAY_SETTINGS"
        "ACTION_WALLPAPER"              -> "android.intent.action.SET_WALLPAPER"
        "ACTION_FONT_SCALING"           -> "android.settings.ACCESSIBILITY_SETTINGS"
        "ACTION_SOUND_SETTINGS",
        "ACTION_RINGTONE",
        "ACTION_MEDIA_VOLUME",
        "ACTION_VIBRATION",
        "ACTION_EQUALIZER",
        "ACTION_SPATIAL_AUDIO",
        "ACTION_DOLBY",
        "ACTION_MEDIA_CONTROLS"         -> "android.settings.SOUND_SETTINGS"
        "ACTION_DND",
        "ACTION_FOCUS_MODE",
        "ACTION_SLEEP_SETTINGS"         -> "android.settings.ZEN_MODE_PRIORITY_SETTINGS"
        "ACTION_WIFI_SETTINGS",
        "ACTION_WIFI_DIRECT",
        "ACTION_NEARBY_SHARE"           -> "android.settings.WIFI_SETTINGS"
        "ACTION_BLUETOOTH_SETTINGS"     -> "android.settings.BLUETOOTH_SETTINGS"
        "ACTION_AIRPLANE_MODE"          -> "android.settings.AIRPLANE_MODE_SETTINGS"
        "ACTION_DATA_USAGE"             -> "android.settings.DATA_USAGE_SETTINGS"
        "ACTION_MOBILE_NETWORK"         -> "android.settings.NETWORK_OPERATOR_SETTINGS"
        "ACTION_HOTSPOT"                -> "android.settings.TETHER_SETTINGS"
        "ACTION_VPN"                    -> "android.settings.VPN_SETTINGS"
        "ACTION_NFC"                    -> "android.settings.NFC_SETTINGS"
        "ACTION_CAST"                   -> "android.settings.CAST_SETTINGS"
        "ACTION_PRIVACY_SETTINGS"       -> "android.settings.PRIVACY_SETTINGS"
        "ACTION_LOCATION_SETTINGS",
        "ACTION_COMPASS"                -> "android.settings.LOCATION_SOURCE_SETTINGS"
        "ACTION_SECURITY_SETTINGS",
        "ACTION_FINGERPRINT",
        "ACTION_FACE_UNLOCK",
        "ACTION_SCREEN_LOCK",
        "ACTION_TWO_FACTOR_AUTH",
        "ACTION_APP_LOCK",
        "ACTION_EMERGENCY_SOS",
        "ACTION_DEVICE_ADMIN"           -> "android.settings.SECURITY_SETTINGS"
        "ACTION_APP_PERMISSIONS"        -> "android.settings.APPLICATION_DETAILS_SETTINGS"
        "ACTION_NOTIFICATION_SETTINGS"  -> "android.settings.APP_NOTIFICATION_SETTINGS"
        "ACTION_DEFAULT_APPS"           -> "android.settings.MANAGE_DEFAULT_APPS_SETTINGS"
        "ACTION_DEVELOPER_OPTIONS"      -> "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"
        "ACTION_SYSTEM_UPDATE"          -> "android.settings.SYSTEM_UPDATE_SETTINGS"
        "ACTION_ABOUT_PHONE"            -> "android.settings.DEVICE_INFO_SETTINGS"
        "ACTION_ACCESSIBILITY",
        "ACTION_COLOUR_CORRECTION",
        "ACTION_TALKBACK",
        "ACTION_GESTURES"               -> "android.settings.ACCESSIBILITY_SETTINGS"
        "ACTION_KEYBOARD",
        "ACTION_SPELL_CHECK",
        "ACTION_VOICE_INPUT"            -> "android.settings.INPUT_METHOD_SETTINGS"
        "ACTION_LANGUAGE"               -> "android.settings.LOCALE_SETTINGS"
        "ACTION_DATE_TIME",
        "ACTION_TIMEZONE",
        "ACTION_24H_FORMAT",
        "ACTION_AUTO_TIME"              -> "android.settings.DATE_SETTINGS"
        "ACTION_ALARM"                  -> "android.intent.action.SET_ALARM"
        "ACTION_CALENDAR_SETTINGS"      -> "android.settings.DATE_SETTINGS"
        "ACTION_GALLERY"                -> "android.intent.action.VIEW"
        "ACTION_DIGITAL_WELLBEING"      -> "android.settings.SETTINGS"
        "ACTION_HEALTH_SETTINGS",
        "ACTION_STEPS_COUNTER",
        "ACTION_STRESS_MONITOR",
        "ACTION_SMART_HOME",
        "ACTION_ROUTINES"               -> "android.settings.APPLICATION_SETTINGS"
        else                            -> "android.settings.SETTINGS"
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Wake Word — continuous background listening
    //
    //  Uses Android's built-in SpeechRecognizer in a continuous loop.
    //  No custom model needed — same engine as the keyboard microphone.
    //
    //  Wake words: "axiom", "hey axiom", "ok axiom", "axium" (typo variant)
    //
    //  Flow:
    //    1. startWakeWordListening() starts the recognizer in short bursts
    //    2. onResults checks for wake word in transcript
    //    3. If found → sends broadcast to MainActivity to activate full voice input
    //    4. onEndOfSpeech/onError → restart loop after short delay
    //
    //  Battery: SpeechRecognizer uses the device VAD (Voice Activity Detection)
    //  chip — same as "Ok Google". Battery drain is minimal (~1-2% per hour).
    //  Paused when MainActivity is in foreground (user can tap mic directly).
    // ════════════════════════════════════════════════════════════════════════

    private var wakeRecognizer: SpeechRecognizer? = null
    private var wakeListening  = false
    private var wakePaused     = false
    private val wakeHandler    = Handler(Looper.getMainLooper())

    private val wakeRestartRunnable = Runnable {
        if (!wakePaused) startWakeWordListening()
    }

    fun startWakeWordListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            android.util.Log.w(TAG, "Wake word: SpeechRecognizer not available")
            return
        }
        if (wakeListening) return

        wakeRecognizer?.destroy()
        wakeRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        wakeRecognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onReadyForSpeech(p: Bundle?) { wakeListening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEvent(t: Int, p: Bundle?) {}

            override fun onPartialResults(partial: Bundle?) {
                // Check partial results for wake word — activates faster
                val text = partial
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim()?.lowercase() ?: return
                if (WAKE_WORDS.any { word -> text.contains(word) }) {
                    android.util.Log.i(TAG, "Wake word detected (partial): '$text'")
                    onWakeWordDetected()
                }
            }

            override fun onResults(results: Bundle?) {
                wakeListening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim()?.lowercase() ?: ""
                android.util.Log.d(TAG, "Wake loop heard: '$text'")
                if (WAKE_WORDS.any { word -> text.contains(word) }) {
                    android.util.Log.i(TAG, "Wake word detected: '$text'")
                    onWakeWordDetected()
                } else {
                    // Not a wake word — restart listening after 300ms
                    wakeHandler.postDelayed(wakeRestartRunnable, 300)
                }
            }

            override fun onEndOfSpeech() {
                wakeListening = false
                // Restart after brief pause to avoid tight loop
                wakeHandler.postDelayed(wakeRestartRunnable, 500)
            }

            override fun onError(error: Int) {
                wakeListening = false
                // Some errors are permanent (no mic permission, not available)
                val permanent = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                val delay = if (permanent) 10_000L else 1_500L
                android.util.Log.d(TAG, "Wake loop error $error — retry in ${delay}ms")
                if (!permanent) wakeHandler.postDelayed(wakeRestartRunnable, delay)
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Short timeout — we restart the loop, so short segments are fine
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }
        runCatching {
            wakeRecognizer?.startListening(intent)
            android.util.Log.d(TAG, "Wake word: listening…")
        }.onFailure {
            android.util.Log.e(TAG, "Wake word startListening failed: ${it.message}")
            wakeHandler.postDelayed(wakeRestartRunnable, 2000)
        }
    }

    fun stopWakeWordListening() {
        wakeHandler.removeCallbacks(wakeRestartRunnable)
        wakeListening = false
        wakeRecognizer?.destroy()
        wakeRecognizer = null
        android.util.Log.i(TAG, "Wake word: stopped")
    }

    fun pauseWakeWordListening() {
        wakePaused = true
        wakeHandler.removeCallbacks(wakeRestartRunnable)
        wakeRecognizer?.stopListening()
        wakeListening = false
        android.util.Log.d(TAG, "Wake word: paused (UI in foreground)")
    }

    fun resumeWakeWordListening() {
        wakePaused = false
        wakeHandler.postDelayed(wakeRestartRunnable, 1000)
        android.util.Log.d(TAG, "Wake word: resumed")
    }

    private fun onWakeWordDetected() {
        // Stop wake loop — MainActivity will restart it on pause
        wakeHandler.removeCallbacks(wakeRestartRunnable)
        wakeListening = false
        wakeRecognizer?.stopListening()
        // Broadcast to MainActivity to open full voice input
        val wakeIntent = Intent(ACTION_WAKE_WORD_TRIGGERED)
        wakeIntent.`package` = packageName
        sendBroadcast(wakeIntent)
    }
}