package com.axiom.axiomnew

// ════════════════════════════════════════════════════════════════════════════════
//  AxiomEngine — single JNI bridge, shared by all Kotlin components
//
//  v0.4 additions:
//    • conversationalAnswer(prompt)  — LLM in free-text answer mode
//    • isEngineReady()               — Kotlin-side volatile flag (set by AxiomService)
// ════════════════════════════════════════════════════════════════════════════════
object AxiomEngine {

    init { System.loadLibrary("axiom_engine") }

    // Set true by AxiomService after initAxiomEngine() succeeds.
    // BatteryDoctor / InsightEngine / PhoneModeManager check this before LLM calls.
    @Volatile var ready: Boolean = false
    fun isEngineReady(): Boolean = ready

    // ── Core lifecycle ────────────────────────────────────────────────────────
    external fun initAxiomEngine(
        modelPath:   String,
        logPath:     String,
        predPath:    String,
        adapterPath: String
    ): Boolean

    external fun shutdownAxiomEngine()

    // ── Sensor state ──────────────────────────────────────────────────────────
    external fun setSensorContext(
        batteryLevel:  Int,
        isCharging:    Boolean,
        wifiConnected: Boolean,
        bluetoothOn:   Boolean,
        hourOfDay:     Int,
        freeStorageMb: Long,
        brightness:    Int,   // 0-255 screen brightness, -1=unknown
        volumePct:     Int,   // 0-100 media volume %, -1=unknown
        ringerMode:    Int    // 0=silent 1=vibrate 2=normal, -1=unknown
    )

    // ── Intent classification (original) ──────────────────────────────────────
    external fun inferIntent(prompt: String): String

    // ── Proactive suggestion ──────────────────────────────────────────────────
    external fun proactiveSuggest(): String

    // ── Feedback ─────────────────────────────────────────────────────────────
    external fun registerFeedback(intent: String, accepted: Int)
    external fun replayPendingFeedback(pendingFilePath: String): Int

    // ── Stats ─────────────────────────────────────────────────────────────────
    external fun getAdapterStats(): String

    // ── Dream learning ────────────────────────────────────────────────────────
    // Batch-trains the adapter from axiom_events.jsonl. Run overnight while charging.
    // Returns JSON: {"trained":N,"events":M,"skipped":K,"adapter":{...}}
    external fun runDreamLearning(): String

    // ── NEW v0.4: Free-text conversational answer ─────────────────────────────
    // Passes prompt to LLM without intent-token logit bias.
    // Returns up to 200 tokens of plain text. Blocking — call off main thread.
    // C++ side uses temperature=0.7, top_p=0.9, no forced vocabulary.
    // Free-text LLM chat reply. Native side wraps user_message in Qwen2.5 chat template.
    // max_tokens: 120 = chat reply (2-3 sentences), 180 = BatteryDoctor/InsightEngine.
    external fun conversationalAnswer(prompt: String, maxTokens: Int = 120): String

    // Intent classification with a pre-built prompt from Kotlin.
    // The prompt already contains <|im_start|>system/user/assistant tokens.
    // Native side decodes it raw (no wrapping) and returns one intent token:
    //   "ACTION_WIFI_SETTINGS", "ACTION_ALARM", "NONE", etc.
    // Greedy sampling, max 20 output tokens — fast (<1s after ARM flags).
    // NOTE: "Cannot resolve JNI function" is an IDE false positive before first
    // clean build. The symbol Java_..._classifyIntent is in native-lib.cpp.
    external fun classifyIntent(prompt: String): String
}