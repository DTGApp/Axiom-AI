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

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

// ════════════════════════════════════════════════════════════════════════════════
//  FileSearchEngine  —  Natural language file search via MediaStore
//
//  Takes a plain-English query, uses the on-device LLM (or keyword fallback)
//  to parse it into structured parameters, then queries MediaStore.
//
//  Example queries:
//    "show images from WhatsApp yesterday"
//    "find PDFs from last week"
//    "videos I took this month"
//    "files from Telegram on Monday"
//    "screenshots from 3 days ago"
//
//  Required permissions (AndroidManifest.xml):
//    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
//        android:maxSdkVersion="32" />
//    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
//    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
//    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
// ════════════════════════════════════════════════════════════════════════════════
class FileSearchEngine(private val ctx: Context) {

    // ── Enums ─────────────────────────────────────────────────────────────────

    enum class FileType   { IMAGE, VIDEO, AUDIO, DOCUMENT, ANY }
    enum class SourceApp  { WHATSAPP, WHATSAPP_BUSINESS, TELEGRAM, CAMERA,
        DOWNLOADS, SCREENSHOTS, ANY }

    // ── Data classes ──────────────────────────────────────────────────────────

    data class SearchParams(
        val fileType:   FileType  = FileType.ANY,
        val sourceApp:  SourceApp = SourceApp.ANY,
        val startMs:    Long      = 0L,
        val endMs:      Long      = Long.MAX_VALUE,
        val query:      String    = "",
        val maxResults: Int       = 50
    )

    data class FileResult(
        val uri:          Uri,
        val displayName:  String,
        val mimeType:     String,
        val sizeBytes:    Long,
        val dateModified: Long,      // epoch ms
        val sourcePath:   String,
        val sourceApp:    SourceApp,
        val fileType:     FileType
    ) {
        val isImage:    Boolean get() = fileType == FileType.IMAGE
        val isVideo:    Boolean get() = fileType == FileType.VIDEO
        val isDocument: Boolean get() = fileType == FileType.DOCUMENT

        // These call the companion object helpers — no outer-class dependency
        val friendlyDate: String get() = formatDateStatic(dateModified)
        val friendlySize: String get() = formatSizeStatic(sizeBytes)
    }

    data class SearchResult(
        val files:      List<FileResult>,
        val totalFound: Int,
        val params:     SearchParams,
        val summary:    String
    )

    // ── Companion object — static helpers & permission check ──────────────────

    companion object {

        private val DOCUMENT_MIMES = setOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
            "text/csv",
            "application/zip",
            "application/x-zip-compressed"
        )

        fun hasPermission(ctx: Context): Boolean =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                ctx.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED ||
                        ctx.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
            }

        /** Static so FileResult.friendlyDate can call it without outer class reference */
        fun formatDateStatic(epochMs: Long): String {
            val now  = System.currentTimeMillis()
            val diff = now - epochMs
            return when {
                diff < TimeUnit.HOURS.toMillis(24)  -> "today"
                diff < TimeUnit.HOURS.toMillis(48)  -> "yesterday"
                diff < TimeUnit.DAYS.toMillis(7)    ->
                    "${TimeUnit.MILLISECONDS.toDays(diff)} days ago"
                else -> {
                    val cal    = Calendar.getInstance().apply { timeInMillis = epochMs }
                    val months = arrayOf("Jan","Feb","Mar","Apr","May","Jun",
                        "Jul","Aug","Sep","Oct","Nov","Dec")
                    "${cal.get(Calendar.DAY_OF_MONTH)} ${months[cal.get(Calendar.MONTH)]}"
                }
            }
        }

        /** Static so FileResult.friendlySize can call it without outer class reference */
        fun formatSizeStatic(bytes: Long): String = when {
            bytes < 1024               -> "$bytes B"
            bytes < 1024 * 1024        -> "${bytes / 1024} KB"
            bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
            else                        -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
        }
    }

    // ── Main entry point ──────────────────────────────────────────────────────

    /**
     * Search files using a natural-language query.
     * Blocking — call off main thread.
     */
    fun search(userQuery: String): SearchResult {
        val params = parseQuery(userQuery)
        android.util.Log.i("FileSearch",
            "type=${params.fileType} src=${params.sourceApp} " +
                    "start=${params.startMs} end=${params.endMs}")
        val files   = queryMediaStore(params)
        val summary = buildSummary(files, params)
        return SearchResult(files, files.size, params, summary)
    }

    // ── Query parsing ─────────────────────────────────────────────────────────

    private fun parseQuery(query: String): SearchParams {
        if (AxiomEngine.isEngineReady()) {
            val prompt = buildParsePrompt(query)
            val raw    = runCatching { AxiomEngine.conversationalAnswer(prompt, 80) }
                .getOrNull()?.trim()
            if (!raw.isNullOrBlank()) {
                val parsed = runCatching { parseParamsFromJson(raw, query) }.getOrNull()
                if (parsed != null) return parsed
            }
        }
        return keywordParseQuery(query)
    }

    private fun buildParsePrompt(query: String): String = buildString {
        appendLine("Extract file search parameters. Respond ONLY with JSON, nothing else.")
        appendLine("""Format: {"file_type":"IMAGE|VIDEO|AUDIO|DOCUMENT|ANY","source_app":"WHATSAPP|WHATSAPP_BUSINESS|TELEGRAM|CAMERA|DOWNLOADS|SCREENSHOTS|ANY","time_ref":"today|yesterday|this_week|last_week|this_month|N_days_ago:N|day_of_week:MONDAY|any"}""")
        appendLine("""Example: "WhatsApp images yesterday" → {"file_type":"IMAGE","source_app":"WHATSAPP","time_ref":"yesterday"}""")
        appendLine("""Example: "PDFs from last week" → {"file_type":"DOCUMENT","source_app":"ANY","time_ref":"last_week"}""")
        appendLine("""Example: "Telegram files Monday" → {"file_type":"ANY","source_app":"TELEGRAM","time_ref":"day_of_week:MONDAY"}""")
        append("""Query: "$query" →""")
    }

    private fun parseParamsFromJson(raw: String, originalQuery: String): SearchParams? {
        val clean = raw.replace("```json","").replace("```","").trim()
        val s = clean.indexOf('{'); val e = clean.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        val json = runCatching { JSONObject(clean.substring(s, e + 1)) }.getOrNull() ?: return null

        val fileType = when (json.optString("file_type","ANY").uppercase()) {
            "IMAGE"    -> FileType.IMAGE
            "VIDEO"    -> FileType.VIDEO
            "AUDIO"    -> FileType.AUDIO
            "DOCUMENT" -> FileType.DOCUMENT
            else       -> FileType.ANY
        }
        val sourceApp = when (json.optString("source_app","ANY").uppercase()) {
            "WHATSAPP"          -> SourceApp.WHATSAPP
            "WHATSAPP_BUSINESS" -> SourceApp.WHATSAPP_BUSINESS
            "TELEGRAM"          -> SourceApp.TELEGRAM
            "CAMERA"            -> SourceApp.CAMERA
            "DOWNLOADS"         -> SourceApp.DOWNLOADS
            "SCREENSHOTS"       -> SourceApp.SCREENSHOTS
            else                -> SourceApp.ANY
        }
        val (startMs, endMs) = resolveTimeRef(json.optString("time_ref","any"))
        return SearchParams(fileType, sourceApp, startMs, endMs, originalQuery)
    }

    private fun keywordParseQuery(query: String): SearchParams {
        val q = query.lowercase()

        val fileType = when {
            q.contains("image") || q.contains("photo") || q.contains("picture") ||
                    q.contains("screenshot") || q.contains("jpg") || q.contains("png")
                -> FileType.IMAGE
            q.contains("video") || q.contains("mp4") || q.contains("reel")
                -> FileType.VIDEO
            q.contains("audio") || q.contains("voice") || q.contains("mp3")
                -> FileType.AUDIO
            q.contains("document") || q.contains("pdf") || q.contains("doc") ||
                    q.contains("word") || q.contains("excel")
                -> FileType.DOCUMENT
            else -> FileType.ANY
        }

        val sourceApp = when {
            q.contains("whatsapp business")       -> SourceApp.WHATSAPP_BUSINESS
            q.contains("whatsapp") || q.contains(" wa ") -> SourceApp.WHATSAPP
            q.contains("telegram")                -> SourceApp.TELEGRAM
            q.contains("camera") || q.contains("took") || q.contains("shot") -> SourceApp.CAMERA
            q.contains("download")                -> SourceApp.DOWNLOADS
            q.contains("screenshot")              -> SourceApp.SCREENSHOTS
            else                                  -> SourceApp.ANY
        }

        val timeRef = when {
            q.contains("today")      -> "today"
            q.contains("yesterday")  -> "yesterday"
            q.contains("this week")  -> "this_week"
            q.contains("last week")  -> "last_week"
            q.contains("this month") -> "this_month"
            q.contains("monday")     -> "day_of_week:MONDAY"
            q.contains("tuesday")    -> "day_of_week:TUESDAY"
            q.contains("wednesday")  -> "day_of_week:WEDNESDAY"
            q.contains("thursday")   -> "day_of_week:THURSDAY"
            q.contains("friday")     -> "day_of_week:FRIDAY"
            q.contains("saturday")   -> "day_of_week:SATURDAY"
            q.contains("sunday")     -> "day_of_week:SUNDAY"
            else -> {
                val d  = Regex("(\\d+)\\s*days?\\s*ago").find(q)?.groupValues?.get(1)?.toIntOrNull()
                val w  = Regex("(\\d+)\\s*weeks?\\s*ago").find(q)?.groupValues?.get(1)?.toIntOrNull()
                val mo = Regex("(\\d+)\\s*months?\\s*ago").find(q)?.groupValues?.get(1)?.toIntOrNull()
                when {
                    d  != null -> "N_days_ago:$d"
                    w  != null -> "N_days_ago:${w * 7}"
                    mo != null -> "N_days_ago:${mo * 30}"
                    else       -> "any"
                }
            }
        }

        val (startMs, endMs) = resolveTimeRef(timeRef)
        return SearchParams(fileType, sourceApp, startMs, endMs, query)
    }

    // ── Time range resolution ──────────────────────────────────────────────────

    private fun resolveTimeRef(timeRef: String): Pair<Long, Long> {
        val endOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59);      set(Calendar.MILLISECOND, 999)
        }

        fun startOfDay(cal: Calendar): Long {
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0);      cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        return when {
            timeRef == "today" -> Pair(startOfDay(Calendar.getInstance()), endOfToday.timeInMillis)

            timeRef == "yesterday" -> {
                val s = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                val e = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, -1)
                    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59);      set(Calendar.MILLISECOND, 999)
                }
                Pair(startOfDay(s), e.timeInMillis)
            }

            timeRef == "this_week" -> {
                val s = Calendar.getInstance().apply {
                    val dow = get(Calendar.DAY_OF_WEEK)
                    val diff = if (dow == Calendar.SUNDAY) -6 else -(dow - Calendar.MONDAY)
                    add(Calendar.DAY_OF_YEAR, diff)
                }
                Pair(startOfDay(s), endOfToday.timeInMillis)
            }

            timeRef == "last_week" -> {
                val thisWeekStart = Calendar.getInstance().apply {
                    val dow  = get(Calendar.DAY_OF_WEEK)
                    val diff = if (dow == Calendar.SUNDAY) -6 else -(dow - Calendar.MONDAY)
                    add(Calendar.DAY_OF_YEAR, diff)
                }
                val endLast = (thisWeekStart.clone() as Calendar).apply { add(Calendar.MILLISECOND, -1) }
                val startLast = (thisWeekStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7) }
                Pair(startOfDay(startLast), endLast.timeInMillis)
            }

            timeRef == "this_month" -> {
                val s = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
                Pair(startOfDay(s), endOfToday.timeInMillis)
            }

            timeRef.startsWith("N_days_ago:") -> {
                val n = timeRef.removePrefix("N_days_ago:").toIntOrNull() ?: 7
                val s = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -n) }
                Pair(startOfDay(s), endOfToday.timeInMillis)
            }

            timeRef.startsWith("day_of_week:") -> {
                val dayName  = timeRef.removePrefix("day_of_week:")
                val targetDow = mapOf("MONDAY" to Calendar.MONDAY, "TUESDAY" to Calendar.TUESDAY,
                    "WEDNESDAY" to Calendar.WEDNESDAY, "THURSDAY" to Calendar.THURSDAY,
                    "FRIDAY" to Calendar.FRIDAY, "SATURDAY" to Calendar.SATURDAY,
                    "SUNDAY" to Calendar.SUNDAY)[dayName] ?: Calendar.MONDAY
                val todayDow  = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
                var daysBack  = (todayDow - targetDow + 7) % 7
                if (daysBack == 0) daysBack = 7
                val s = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -daysBack) }
                val e = (s.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59);      set(Calendar.MILLISECOND, 999)
                }
                Pair(startOfDay(s), e.timeInMillis)
            }

            else -> Pair(0L, Long.MAX_VALUE) // "any"
        }
    }

    // ── MediaStore query ──────────────────────────────────────────────────────

    private fun queryMediaStore(params: SearchParams): List<FileResult> {
        val results = mutableListOf<FileResult>()
        getCollections(params.fileType).forEach { (uri, type) ->
            results += queryCollection(uri, type, params)
        }
        return results.sortedByDescending { it.dateModified }.take(params.maxResults)
    }

    private fun getCollections(type: FileType): List<Pair<Uri, FileType>> = when (type) {
        FileType.IMAGE    -> listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to FileType.IMAGE)
        FileType.VIDEO    -> listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI  to FileType.VIDEO)
        FileType.AUDIO    -> listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI  to FileType.AUDIO)
        FileType.DOCUMENT -> listOf(MediaStore.Files.getContentUri("external")   to FileType.DOCUMENT)
        FileType.ANY      -> listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to FileType.IMAGE,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI  to FileType.VIDEO,
            MediaStore.Files.getContentUri("external")   to FileType.DOCUMENT
        )
    }

    private fun queryCollection(
        collectionUri: Uri, type: FileType, params: SearchParams
    ): List<FileResult> {
        val projection = arrayOf(
            FileColumns._ID, FileColumns.DISPLAY_NAME, FileColumns.MIME_TYPE,
            FileColumns.SIZE, FileColumns.DATE_MODIFIED, FileColumns.DATA
        )

        val selections  = mutableListOf<String>()
        val selArgs     = mutableListOf<String>()

        if (params.startMs > 0L) {
            selections += "${FileColumns.DATE_MODIFIED} >= ?"
            selArgs    += (params.startMs / 1000L).toString()
        }
        if (params.endMs < Long.MAX_VALUE) {
            selections += "${FileColumns.DATE_MODIFIED} <= ?"
            selArgs    += (params.endMs / 1000L).toString()
        }
        if (type == FileType.DOCUMENT) {
            selections += "(${DOCUMENT_MIMES.joinToString(" OR ") { "${FileColumns.MIME_TYPE} = ?" }})"
            selArgs    += DOCUMENT_MIMES.toList()
        }
        getPathFilter(params.sourceApp)?.let { filter ->
            selections += "${FileColumns.DATA} LIKE ?"
            selArgs    += "%$filter%"
        }

        val selection = selections.joinToString(" AND ").ifEmpty { null }
        val results   = mutableListOf<FileResult>()

        runCatching {
            ctx.contentResolver.query(
                collectionUri, projection, selection,
                selArgs.toTypedArray().takeIf { it.isNotEmpty() },
                "${FileColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol   = cursor.getColumnIndexOrThrow(FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(FileColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(FileColumns.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(FileColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(FileColumns.DATE_MODIFIED)
                val pathCol = cursor.getColumnIndexOrThrow(FileColumns.DATA)

                while (cursor.moveToNext() && results.size < params.maxResults) {
                    val id     = cursor.getLong(idCol)
                    val name   = cursor.getString(nameCol) ?: continue
                    val mime   = cursor.getString(mimeCol) ?: ""
                    val size   = cursor.getLong(sizeCol)
                    val dateMs = cursor.getLong(dateCol) * 1000L
                    val path   = cursor.getString(pathCol) ?: ""

                    results += FileResult(
                        uri          = ContentUris.withAppendedId(collectionUri, id),
                        displayName  = name,
                        mimeType     = mime,
                        sizeBytes    = size,
                        dateModified = dateMs,
                        sourcePath   = path,
                        sourceApp    = detectSource(path),
                        fileType     = detectType(mime, type)
                    )
                }
            }
        }.onFailure {
            android.util.Log.e("FileSearch", "MediaStore query failed: ${it.message}")
        }
        return results
    }

    // ── Source detection ──────────────────────────────────────────────────────

    private fun detectSource(path: String): SourceApp {
        val p = path.lowercase()
        return when {
            p.contains("whatsapp/media/whatsapp business") ||
                    p.contains("whatsapp business")      -> SourceApp.WHATSAPP_BUSINESS
            p.contains("whatsapp")                       -> SourceApp.WHATSAPP
            p.contains("telegram")                       -> SourceApp.TELEGRAM
            p.contains("screenshots")                    -> SourceApp.SCREENSHOTS
            p.contains("dcim/camera") || p.contains("/camera/") -> SourceApp.CAMERA
            p.contains("download")                       -> SourceApp.DOWNLOADS
            else                                         -> SourceApp.ANY
        }
    }

    private fun getPathFilter(source: SourceApp): String? = when (source) {
        SourceApp.WHATSAPP          -> "WhatsApp/Media"
        SourceApp.WHATSAPP_BUSINESS -> "WhatsApp Business/Media"
        SourceApp.TELEGRAM          -> "Telegram"
        SourceApp.CAMERA            -> "DCIM/Camera"
        SourceApp.DOWNLOADS         -> "Download"
        SourceApp.SCREENSHOTS       -> "Screenshots"
        SourceApp.ANY               -> null
    }

    private fun detectType(mime: String, hintType: FileType): FileType {
        if (hintType != FileType.ANY && hintType != FileType.DOCUMENT) return hintType
        return when {
            mime.startsWith("image/")       -> FileType.IMAGE
            mime.startsWith("video/")       -> FileType.VIDEO
            mime.startsWith("audio/")       -> FileType.AUDIO
            DOCUMENT_MIMES.contains(mime)   -> FileType.DOCUMENT
            else                            -> FileType.ANY
        }
    }

    // ── Summary ────────────────────────────────────────────────────────────────

    private fun buildSummary(files: List<FileResult>, params: SearchParams): String {
        if (files.isEmpty()) return buildNotFoundSummary(params)

        val imgs = files.count { it.isImage }
        val vids = files.count { it.isVideo }
        val docs = files.count { it.isDocument }
        val rest = files.size - imgs - vids - docs

        val parts = buildList {
            if (imgs > 0) add("$imgs image${if (imgs != 1) "s" else ""}")
            if (vids > 0) add("$vids video${if (vids != 1) "s" else ""}")
            if (docs > 0) add("$docs document${if (docs != 1) "s" else ""}")
            if (rest > 0) add("$rest other")
        }

        val srcStr = when (params.sourceApp) {
            SourceApp.WHATSAPP          -> " from WhatsApp"
            SourceApp.WHATSAPP_BUSINESS -> " from WhatsApp Business"
            SourceApp.TELEGRAM          -> " from Telegram"
            SourceApp.CAMERA            -> " from Camera"
            SourceApp.DOWNLOADS         -> " from Downloads"
            SourceApp.SCREENSHOTS       -> " (screenshots)"
            SourceApp.ANY               -> ""
        }

        val newest = files.firstOrNull()?.friendlyDate ?: ""
        val oldest = files.lastOrNull()?.friendlyDate ?: ""
        val dateRange = if (newest == oldest) newest else "$oldest – $newest"

        return "Found ${parts.joinToString(", ")}$srcStr, $dateRange. " +
                "Total: ${formatSizeStatic(files.sumOf { it.sizeBytes })}"
    }

    private fun buildNotFoundSummary(params: SearchParams): String {
        val typeStr = when (params.fileType) {
            FileType.ANY -> "files"; else -> params.fileType.name.lowercase()
        }
        val srcStr = when (params.sourceApp) {
            SourceApp.ANY -> ""
            else -> " from ${params.sourceApp.name.replace('_',' ').lowercase()}"
        }
        return "No $typeStr$srcStr found in that time range."
    }
}