package com.axiom.axiomnew

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log

// ════════════════════════════════════════════════════════════════════════════════
//  ContactResolver — Resolves spoken contact names to phone numbers
//
//  "call mom on WhatsApp" → resolves "mom" → "+91XXXXXXXXXX"
//
//  Gated on READ_CONTACTS permission — returns null cleanly if not granted.
//  Results are cached in-memory for the session (cleared on process death).
//  Cache is intentionally NOT persisted — contacts can change between sessions.
// ════════════════════════════════════════════════════════════════════════════════
object ContactResolver {

    private const val TAG = "ContactResolver"

    // Session cache: "mom" → "+91XXXXXXXXXX"
    private val cache = mutableMapOf<String, String?>()

    data class ContactInfo(
        val displayName: String,
        val phone:       String,    // E.164-ish, e.g. "+919876543210" or "9876543210"
        val email:       String?
    )

    /**
     * Resolve a spoken name to a ContactInfo.
     * Returns null if: no permission, no match, or empty contacts.
     *
     * Matching strategy (most-specific first):
     *   1. Exact display name match (case-insensitive)
     *   2. Display name starts with the given name
     *   3. Any name part matches (first name only, nickname)
     */
    fun resolve(name: String, context: Context): ContactInfo? {
        val nameLower = name.trim().lowercase()
        if (nameLower.isBlank()) return null

        // Check permission explicitly — lint requires this at call site
        try {
            if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "READ_CONTACTS not granted — skipping contact lookup")
                return null
            }
        } catch (e: SecurityException) {
            return null
        }

        // Cache hit
        if (cache.containsKey(nameLower)) {
            val cached = cache[nameLower] ?: return null
            return ContactInfo(name, cached, null)
        }

        val result = queryContacts(nameLower, context)
        cache[nameLower] = result?.phone
        return result
    }

    /** Clears the session cache (call after contacts change). */
    fun clearCache() = cache.clear()

    // ── Internal query ────────────────────────────────────────────────────────

    private fun queryContacts(nameLower: String, context: Context): ContactInfo? {
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val sel  = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$nameLower%")

        val candidates = mutableListOf<ContactInfo>()

        try {
            val cursor: Cursor? = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                proj, sel, args,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
            cursor?.use { c ->
                while (c.moveToNext()) {
                    val displayName = c.getString(0) ?: continue
                    val number      = c.getString(1) ?: continue
                    if (number.isBlank()) continue
                    val cleanNumber = number.replace(Regex("[\\s\\-()]"), "")
                    candidates += ContactInfo(displayName, cleanNumber, null)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException reading contacts: ${e.message}")
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Contact query error: ${e.message}")
            return null
        }

        if (candidates.isEmpty()) return null

        // Rank: exact match > starts with > any part contains
        val nameLowerTrimmed = nameLower.trim()
        return candidates
            .sortedWith(compareBy {
                val dn = it.displayName.lowercase()
                when {
                    dn == nameLowerTrimmed                   -> 0   // exact
                    dn.startsWith(nameLowerTrimmed)          -> 1   // prefix
                    dn.split(" ").any { p -> p == nameLowerTrimmed } -> 2  // word match
                    else                                     -> 3   // partial
                }
            })
            .firstOrNull()
    }
}