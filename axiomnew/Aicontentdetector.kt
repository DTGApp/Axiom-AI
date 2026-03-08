package com.axiom.axiomnew

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import kotlin.math.abs
import kotlin.math.sqrt

// ════════════════════════════════════════════════════════════════════════════════
//  AiContentDetector  —  On-device AI-generated content detection
//
//  Zero extra dependencies — uses only android.* APIs already in the project.
//
//  TEXT  → LLM analysis + 5 heuristic signals
//  IMAGE → MediaStore metadata columns (camera, GPS, date_taken),
//          JPEG marker scan for software tag,
//          dimension check, colour variance
// ════════════════════════════════════════════════════════════════════════════════
class AiContentDetector(private val ctx: Context) {

    // ── Types ──────────────────────────────────────────────────────────────────

    enum class ContentType { TEXT, IMAGE }
    enum class Verdict     { LIKELY_AI, POSSIBLY_AI, LIKELY_HUMAN, UNCERTAIN }

    data class Signal(
        val name:       String,
        val description:String,
        val pointsToAi: Boolean,
        val weight:     Float
    )

    data class DetectionResult(
        val verdict:     Verdict,
        val confidence:  Int,
        val summary:     String,
        val signals:     List<Signal>,
        val llmAnalysis: String?,
        val contentType: ContentType
    ) {
        val verdictLabel: String get() = when (verdict) {
            Verdict.LIKELY_AI    -> "🤖 Likely AI-Generated"
            Verdict.POSSIBLY_AI  -> "⚠️ Possibly AI-Generated"
            Verdict.LIKELY_HUMAN -> "✅ Likely Human-Created"
            Verdict.UNCERTAIN    -> "❓ Uncertain"
        }
        val verdictColor: Int get() = when (verdict) {
            Verdict.LIKELY_AI    -> android.graphics.Color.parseColor("#FF5252")
            Verdict.POSSIBLY_AI  -> android.graphics.Color.parseColor("#FFB300")
            Verdict.LIKELY_HUMAN -> android.graphics.Color.parseColor("#69F0AE")
            Verdict.UNCERTAIN    -> android.graphics.Color.parseColor("#90A4AE")
        }
    }

    // ── AI phrase list ─────────────────────────────────────────────────────────

    private val AI_PHRASES = listOf(
        "certainly", "of course", "absolutely", "i'd be happy to", "i would be happy",
        "it's worth noting", "it is worth noting", "it's important to note",
        "in conclusion", "to summarize", "in summary",
        "furthermore", "moreover", "additionally",
        "as an ai", "as a language model",
        "delve into", "dive into", "shed light", "navigate the",
        "in today's world", "in the ever-evolving",
        "leverage", "utilize", "foster", "paramount", "crucial",
        "it goes without saying", "needless to say",
        "comprehensive", "multifaceted", "nuanced perspective",
        "i hope this helps", "let me know if you", "feel free to ask",
        "rest assured", "without further ado", "having said that",
        "overall,", "in essence,", "to elaborate"
    )

    private val AI_IMAGE_TOOLS = listOf(
        "stable diffusion", "midjourney", "dall-e", "dalle", "adobe firefly",
        "imagen", "ideogram", "leonardo", "canva ai", "automatic1111",
        "comfyui", "novelai", "playground ai", "dreamstudio", "bing image"
    )

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Blocking — call off main thread. */
    fun analyseText(text: String): DetectionResult {
        if (text.isBlank())
            return uncertainResult(ContentType.TEXT, "No text provided.")
        val wordCount = text.trim().split(Regex("\\s+")).size
        if (wordCount < 20)
            return uncertainResult(ContentType.TEXT,
                "Text too short. Please paste at least 50 words for a reliable result.")
        val features = extractTextFeatures(text)
        val llmRaw   = if (AxiomEngine.isEngineReady()) runLlmAnalysis(text) else null
        return buildTextResult(features, llmRaw, text)
    }

    /** Blocking — call off main thread. */
    fun analyseImage(uri: Uri): DetectionResult {
        val signals = mutableListOf<Signal>()
        signals += analyseMediaStoreMetadata(uri)
        signals += analyseDimensions(uri)
        signals += analyseJpegSoftwareTag(uri)
        signals += analyseColours(uri)
        return buildImageResult(signals)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TEXT ANALYSIS
    // ══════════════════════════════════════════════════════════════════════════

    private data class TextFeatures(
        val avgSentenceLen:    Double,
        val sentenceLenStdDev: Double,
        val typeTokenRatio:    Double,
        val aiPhraseCount:     Int,
        val aiPhraseMatches:   List<String>,
        val burstinessScore:   Double,
        val punctuationScore:  Double,
        val paragraphCount:    Int,
        val wordCount:         Int
    )

    private fun extractTextFeatures(text: String): TextFeatures {
        val sentences   = text.split(Regex("[.!?]+\\s+")).filter { it.trim().length > 5 }
        val words       = text.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        val uniqueWords = words.toSet()

        // Sentence length stats
        val sentLens = sentences.map { it.split(" ").size.toDouble() }
        val avgLen   = if (sentLens.isEmpty()) 0.0 else sentLens.average()
        val stdDev   = if (sentLens.size < 2) 0.0 else
            sqrt(sentLens.map { (it - avgLen) * (it - avgLen) }.average())

        // Vocabulary diversity
        val ttr = if (words.isEmpty()) 0.0
        else uniqueWords.size.toDouble() / words.size

        // AI phrase detection
        val lower         = text.lowercase()
        val phraseMatches = AI_PHRASES.filter { lower.contains(it) }

        // Burstiness: variance / mean of word frequencies
        val freq     = words.groupBy { it }.mapValues { it.value.size.toDouble() }
        val freqVals = freq.values.toList()
        val freqMean = if (freqVals.isEmpty()) 0.0 else freqVals.average()
        val freqVar  = if (freqVals.size < 2) 0.0
        else freqVals.map { (it - freqMean) * (it - freqMean) }.average()
        val burstiness = if (freqMean < 0.001) 0.0 else freqVar / freqMean

        // Punctuation uniformity
        val periodEnds = sentences.count { it.trim().endsWith(".") }
        val punctScore = if (sentences.isEmpty()) 0.0
        else periodEnds.toDouble() / sentences.size

        val paragraphs = text.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }

        return TextFeatures(
            avgSentenceLen    = avgLen,
            sentenceLenStdDev = stdDev,
            typeTokenRatio    = ttr,
            aiPhraseCount     = phraseMatches.size,
            aiPhraseMatches   = phraseMatches.take(5),
            burstinessScore   = burstiness,
            punctuationScore  = punctScore,
            paragraphCount    = paragraphs.size,
            wordCount         = words.size
        )
    }

    private fun runLlmAnalysis(text: String): String? {
        val sample = text.take(700)
        // Build prompt — avoid triple-quote inside heredoc by using string concat
        val prompt = "<|im_start|>system\n" +
                "You are an AI content detector. Output ONLY valid JSON.\n" +
                "Format: {\"verdict\":\"AI\" or \"HUMAN\" or \"UNCERTAIN\"," +
                "\"confidence\":0-100,\"reason\":\"one short sentence\"}\n" +
                "<|im_end|>\n<|im_start|>user\n" +
                "Does this text appear AI-generated? Look for: unnatural uniformity, " +
                "AI filler phrases, lack of personal voice, overly structured sentences.\n\n" +
                sample + "\n<|im_end|>\n<|im_start|>assistant\n"

        return runCatching { AxiomEngine.conversationalAnswer(prompt, 80) }
            .getOrNull()?.trim()
    }

    private fun buildTextResult(
        f: TextFeatures, llmRaw: String?, text: String
    ): DetectionResult {
        val signals = mutableListOf<Signal>()

        // Signal: AI filler phrases
        when {
            f.aiPhraseCount >= 3 ->
                signals += Signal("AI Filler Phrases",
                    "Found ${f.aiPhraseCount} typical AI phrases: ${f.aiPhraseMatches.joinToString(", ")}",
                    pointsToAi = true, weight = 0.85f)
            f.aiPhraseCount in 1..2 ->
                signals += Signal("Some AI Phrases",
                    "Found ${f.aiPhraseCount} AI-associated phrase(s): ${f.aiPhraseMatches.joinToString(", ")}",
                    pointsToAi = true, weight = 0.35f)
            else ->
                signals += Signal("No AI Filler Phrases",
                    "None of the common AI filler phrases were found.",
                    pointsToAi = false, weight = 0.40f)
        }

        // Signal: Sentence uniformity
        if (f.sentenceLenStdDev < 4.0 && f.avgSentenceLen > 8.0) {
            signals += Signal("Uniform Sentence Length",
                "Sentences are unusually even (std dev %.1f words). AI writes with machine-like consistency.".format(f.sentenceLenStdDev),
                pointsToAi = true, weight = 0.65f)
        } else if (f.sentenceLenStdDev > 8.0) {
            signals += Signal("Natural Sentence Variation",
                "Sentence length varies naturally (std dev %.1f words).".format(f.sentenceLenStdDev),
                pointsToAi = false, weight = 0.45f)
        }

        // Signal: Vocabulary diversity
        if (f.typeTokenRatio < 0.44 && text.length > 300) {
            signals += Signal("Low Vocabulary Diversity",
                "Only %.0f%% unique words — suggests repetitive AI phrasing.".format(f.typeTokenRatio * 100),
                pointsToAi = true, weight = 0.55f)
        } else if (f.typeTokenRatio > 0.68) {
            signals += Signal("Rich Vocabulary",
                "%.0f%% unique words — diverse vocabulary typical of human writing.".format(f.typeTokenRatio * 100),
                pointsToAi = false, weight = 0.40f)
        }

        // Signal: Burstiness
        if (f.burstinessScore < 2.0 && f.wordCount > 100) {
            signals += Signal("Low Text Burstiness",
                "Word frequency is too uniform. Human writing naturally clusters words by topic.",
                pointsToAi = true, weight = 0.50f)
        }

        // Signal: Punctuation uniformity
        if (f.punctuationScore > 0.92 && f.paragraphCount > 2) {
            signals += Signal("Mechanical Punctuation",
                "%.0f%% of sentences end with a period — unusually mechanical.".format(f.punctuationScore * 100),
                pointsToAi = true, weight = 0.35f)
        }

        // Signal: LLM deep analysis
        var llmSummary: String? = null
        if (llmRaw != null) {
            runCatching {
                val s = llmRaw.indexOf('{'); val e = llmRaw.lastIndexOf('}')
                if (s >= 0 && e > s) {
                    val json    = org.json.JSONObject(llmRaw.substring(s, e + 1))
                    val verdict = json.optString("verdict", "UNCERTAIN").uppercase()
                    val conf    = json.optInt("confidence", 50)
                    val reason  = json.optString("reason", "")
                    llmSummary  = "LLM: $verdict ($conf%) — $reason"
                    signals += Signal("LLM Deep Analysis", llmSummary!!,
                        pointsToAi = verdict == "AI",
                        weight     = (conf / 100f) * 0.9f)
                }
            }
        } else if (!AxiomEngine.isEngineReady()) {
            signals += Signal("LLM Not Ready",
                "Engine still loading — heuristics only. Re-run after model loads.",
                pointsToAi = false, weight = 0.0f)
        }

        // Score
        val aiScore    = signals.filter {  it.pointsToAi }.sumOf { it.weight.toDouble() }
        val humanScore = signals.filter { !it.pointsToAi }.sumOf { it.weight.toDouble() }
        val total      = aiScore + humanScore
        val aiPct      = if (total < 0.001) 50.0 else (aiScore / total) * 100.0

        val verdict = when {
            aiPct >= 70 -> Verdict.LIKELY_AI
            aiPct >= 55 -> Verdict.POSSIBLY_AI
            aiPct <= 38 -> Verdict.LIKELY_HUMAN
            else        -> Verdict.UNCERTAIN
        }
        val confidence = when (verdict) {
            Verdict.LIKELY_AI    -> aiPct.toInt().coerceIn(58, 93)
            Verdict.POSSIBLY_AI  -> (45 + (aiPct - 55) * 1.5).toInt().coerceIn(46, 65)
            Verdict.LIKELY_HUMAN -> (100 - aiPct).toInt().coerceIn(55, 91)
            Verdict.UNCERTAIN    -> 40
        }

        val topAi    = signals.filter {  it.pointsToAi }.maxByOrNull { it.weight }
        val topHuman = signals.filter { !it.pointsToAi }.maxByOrNull { it.weight }

        val summary = when (verdict) {
            Verdict.LIKELY_AI ->
                "Strong signs of AI generation ($confidence% confidence).\n\n" +
                        (topAi?.let { "Key indicator: ${it.description}\n\n" } ?: "") +
                        "AI phrases: ${f.aiPhraseCount}  |  Sentence std dev: %.1f words".format(f.sentenceLenStdDev)
            Verdict.POSSIBLY_AI ->
                "Some AI-like patterns but not conclusive.\n\n" +
                        (topAi?.let    { "Suspicious: ${it.name}. " }         ?: "") +
                        (topHuman?.let { "Human indicator: ${it.name}." }    ?: "")
            Verdict.LIKELY_HUMAN ->
                "Appears human-written ($confidence% confidence).\n\n" +
                        (topHuman?.let { it.description } ?: "")
            Verdict.UNCERTAIN ->
                "Not enough signal to determine origin. " +
                        "Try a longer sample (100+ words) for better accuracy."
        }

        return DetectionResult(verdict, confidence, summary, signals, llmSummary, ContentType.TEXT)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  IMAGE ANALYSIS — no ExifInterface dependency
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Uses MediaStore cursor columns to read metadata.
     * Columns like DATE_TAKEN, LATITUDE, LONGITUDE are populated by the
     * camera app and are absent in AI-generated images saved to the gallery.
     */
    private fun analyseMediaStoreMetadata(uri: Uri): List<Signal> {
        val signals = mutableListOf<Signal>()
        runCatching {
            val proj = arrayOf(
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.LATITUDE,
                MediaStore.Images.Media.LONGITUDE,
                MediaStore.Images.Media.DESCRIPTION,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATA
            )
            ctx.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return signals

                val dateTaken = runCatching {
                    c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))
                }.getOrDefault(0L)

                val lat = runCatching {
                    c.getDouble(c.getColumnIndexOrThrow(MediaStore.Images.Media.LATITUDE))
                }.getOrDefault(0.0)

                val lon = runCatching {
                    c.getDouble(c.getColumnIndexOrThrow(MediaStore.Images.Media.LONGITUDE))
                }.getOrDefault(0.0)

                val desc = runCatching {
                    c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DESCRIPTION)) ?: ""
                }.getOrDefault("")

                val path = runCatching {
                    c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)) ?: ""
                }.getOrDefault("")

                // Check date_taken
                if (dateTaken == 0L) {
                    signals += Signal("No Capture Timestamp",
                        "DATE_TAKEN is missing — camera apps always record this when a photo is taken.",
                        pointsToAi = true, weight = 0.55f)
                } else {
                    signals += Signal("Capture Timestamp Present",
                        "Photo was taken on a specific date — consistent with a real camera.",
                        pointsToAi = false, weight = 0.45f)
                }

                // GPS coords
                if (lat == 0.0 && lon == 0.0) {
                    signals += Signal("No GPS Coordinates",
                        "No location data. Note: many real photos also lack GPS (location off, screenshots, scanned).",
                        pointsToAi = true, weight = 0.20f)
                } else {
                    signals += Signal("GPS Coordinates Present",
                        "Photo has GPS coordinates — strong indicator of a real camera shot.",
                        pointsToAi = false, weight = 0.65f)
                }

                // Description / software tag via description column
                if (desc.isNotBlank()) {
                    val d   = desc.lowercase()
                    val hit = AI_IMAGE_TOOLS.firstOrNull { d.contains(it) }
                    if (hit != null) {
                        signals += Signal("AI Tool in Description",
                            "Description contains: \"$hit\" — a known AI image generator.",
                            pointsToAi = true, weight = 1.0f)
                    }
                }

                // File path hints (e.g. saved from AI app)
                if (path.isNotBlank()) {
                    val p   = path.lowercase()
                    val hit = AI_IMAGE_TOOLS.firstOrNull { p.contains(it.replace(" ", "").replace("-", "")) }
                    if (hit != null) {
                        signals += Signal("AI Tool in File Path",
                            "File path suggests AI origin: $path",
                            pointsToAi = true, weight = 0.85f)
                    }
                }
            }
        }.onFailure {
            android.util.Log.w("AiDetector", "MediaStore query failed: ${it.message}")
        }
        return signals
    }

    /**
     * Scans the first 64 KB of the file for JPEG APP1 markers.
     * Looks for ASCII "Software" tag value — if it names an AI tool, that's a
     * near-certain indicator. No ExifInterface needed.
     */
    private fun analyseJpegSoftwareTag(uri: Uri): List<Signal> {
        val signals = mutableListOf<Signal>()
        runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().take(65536).toByteArray() }
                ?: return signals

            // Convert to lowercase string for simple text search
            // EXIF software tag is stored as ASCII in the JPEG binary
            val raw = String(bytes, Charsets.ISO_8859_1).lowercase()

            val hit = AI_IMAGE_TOOLS.firstOrNull { raw.contains(it) }
            if (hit != null) {
                signals += Signal("AI Tool Signature in File",
                    "File header contains reference to AI generator: \"$hit\"",
                    pointsToAi = true, weight = 1.0f)
            }

            // Check for "camera" or maker note keywords (NIKON, CANON, SAMSUNG etc.)
            val cameraKeywords = listOf("nikon", "canon", "sony", "samsung", "apple", "google",
                "huawei", "xiaomi", "oneplus", "motorola", "lg electronics", "fujifilm",
                "olympus", "panasonic", "leica", "gopro")
            val cameraHit = cameraKeywords.firstOrNull { raw.contains(it) }
            if (cameraHit != null) {
                signals += Signal("Camera Maker Tag Found",
                    "File header contains camera maker: \"${cameraHit.replaceFirstChar { it.uppercase() }}\"",
                    pointsToAi = false, weight = 0.70f)
            } else if (hit == null) {
                // No camera maker AND no AI tool found
                signals += Signal("No Camera Maker in Header",
                    "Could not find any camera manufacturer in the file header.",
                    pointsToAi = true, weight = 0.40f)
            }
        }.onFailure {
            android.util.Log.w("AiDetector", "JPEG scan failed: ${it.message}")
        }
        return signals
    }

    private fun analyseDimensions(uri: Uri): List<Signal> {
        val signals = mutableListOf<Signal>()
        runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            val w = opts.outWidth; val h = opts.outHeight
            if (w <= 0 || h <= 0) return signals

            // AI generators predominantly produce multiples of 64px
            // Real cameras produce specific sensor resolutions
            val aiDims = setOf(512, 576, 640, 704, 768, 832, 896, 960,
                1024, 1152, 1280, 1344, 1408, 1536, 2048)

            when {
                aiDims.contains(w) && aiDims.contains(h) ->
                    signals += Signal("AI-Standard Dimensions",
                        "${w}×${h}px — both dimensions are AI generator standard sizes (multiples of 64/128).",
                        pointsToAi = true, weight = 0.65f)
                aiDims.contains(w) || aiDims.contains(h) ->
                    signals += Signal("Partial AI Dimensions",
                        "One dimension of ${w}×${h}px matches AI generator sizes.",
                        pointsToAi = true, weight = 0.30f)
                else -> {
                    val ratio        = w.toDouble() / h
                    val cameraRatios = listOf(4.0/3, 3.0/2, 16.0/9, 1.0, 9.0/16, 3.0/4, 2.0/3)
                    if (cameraRatios.any { abs(ratio - it) < 0.06 }) {
                        signals += Signal("Natural Camera Aspect Ratio",
                            "${w}×${h}px — aspect ratio matches standard camera formats.",
                            pointsToAi = false, weight = 0.35f)
                    }
                }
            }
        }.onFailure {
            android.util.Log.w("AiDetector", "Dimension check failed: ${it.message}")
        }
        return signals
    }

    private fun analyseColours(uri: Uri): List<Signal> {
        val signals = mutableListOf<Signal>()
        runCatching {
            val bitmap: Bitmap = ctx.contentResolver.openInputStream(uri)?.use { stream ->
                val full   = BitmapFactory.decodeStream(stream) ?: return signals
                val scaled = Bitmap.createScaledBitmap(full, 80, 80, true)
                if (scaled !== full) full.recycle()
                scaled
            } ?: return signals

            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap.recycle()

            var rSum = 0L; var gSum = 0L; var bSum = 0L
            pixels.forEach { p ->
                rSum += (p shr 16 and 0xFF)
                gSum += (p shr  8 and 0xFF)
                bSum += (p        and 0xFF)
            }
            val n     = pixels.size.toDouble()
            val rMean = rSum / n; val gMean = gSum / n; val bMean = bSum / n

            var rVar = 0.0; var gVar = 0.0; var bVar = 0.0
            pixels.forEach { p ->
                val r = (p shr 16 and 0xFF).toDouble()
                val g = (p shr  8 and 0xFF).toDouble()
                val b = (p        and 0xFF).toDouble()
                rVar += (r - rMean) * (r - rMean)
                gVar += (g - gMean) * (g - gMean)
                bVar += (b - bMean) * (b - bMean)
            }
            val avgStd = (sqrt(rVar / n) + sqrt(gVar / n) + sqrt(bVar / n)) / 3.0

            when {
                avgStd < 18.0 ->
                    signals += Signal("Unusually Flat Colours",
                        "Very low colour variance (σ=%.1f) — may indicate AI-rendered or gradient image.".format(avgStd),
                        pointsToAi = true, weight = 0.40f)
                avgStd > 78.0 ->
                    signals += Signal("Hyper-Saturated Colours",
                        "Very high colour variance (σ=%.1f) — may indicate AI hyper-saturation.".format(avgStd),
                        pointsToAi = true, weight = 0.25f)
                else ->
                    signals += Signal("Natural Colour Distribution",
                        "Colour variance looks natural (σ=%.1f).".format(avgStd),
                        pointsToAi = false, weight = 0.30f)
            }
        }.onFailure {
            android.util.Log.w("AiDetector", "Colour analysis failed: ${it.message}")
        }
        return signals
    }

    private fun buildImageResult(signals: List<Signal>): DetectionResult {
        val aiScore    = signals.filter {  it.pointsToAi }.sumOf { it.weight.toDouble() }
        val humanScore = signals.filter { !it.pointsToAi }.sumOf { it.weight.toDouble() }
        val total      = aiScore + humanScore
        val aiPct      = if (total < 0.001) 50.0 else (aiScore / total) * 100.0

        val verdict = when {
            aiPct >= 72 -> Verdict.LIKELY_AI
            aiPct >= 55 -> Verdict.POSSIBLY_AI
            aiPct <= 35 -> Verdict.LIKELY_HUMAN
            else        -> Verdict.UNCERTAIN
        }
        val confidence = when (verdict) {
            Verdict.LIKELY_AI    -> aiPct.toInt().coerceIn(55, 88)
            Verdict.POSSIBLY_AI  -> (40 + (aiPct - 55) * 1.2).toInt().coerceIn(41, 62)
            Verdict.LIKELY_HUMAN -> (100 - aiPct).toInt().coerceIn(50, 86)
            Verdict.UNCERTAIN    -> 35
        }

        val topAi = signals.filter { it.pointsToAi }.maxByOrNull { it.weight }

        val summary = when (verdict) {
            Verdict.LIKELY_AI ->
                "Strong signs of AI generation ($confidence% confidence).\n\n" +
                        (topAi?.let { "Key indicator: ${it.description}\n\n" } ?: "") +
                        "Note: Without a vision model, detection relies on metadata and statistics. " +
                        "For maximum accuracy, use a dedicated web-based AI image detector too."
            Verdict.POSSIBLY_AI ->
                "Some AI-typical characteristics but inconclusive.\n\n" +
                        "Metadata and dimensions suggest possible AI origin. " +
                        "Consider verifying with a dedicated AI image detector."
            Verdict.LIKELY_HUMAN ->
                "Appears to be a real photograph ($confidence% confidence).\n\n" +
                        "Camera metadata and natural dimensions support this."
            Verdict.UNCERTAIN ->
                "Cannot determine if AI-generated.\n\n" +
                        "Insufficient or ambiguous metadata. " +
                        "Try a dedicated AI image detector for more reliable results."
        }

        return DetectionResult(verdict, confidence, summary, signals, null, ContentType.IMAGE)
    }

    private fun uncertainResult(type: ContentType, msg: String) = DetectionResult(
        verdict = Verdict.UNCERTAIN, confidence = 0, summary = msg,
        signals = emptyList(), llmAnalysis = null, contentType = type
    )
}