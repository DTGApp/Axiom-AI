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

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.widget.Toast

// ════════════════════════════════════════════════════════════════════════════
//  ModeConfirmReceiver — "Yes, switch" / "Not now" on mode notifications
//
//  All logic is self-contained so this compiles regardless of which version
//  of PhoneModeManager is currently in the project.
//
//  AndroidManifest.xml:
//    <receiver android:name=".ModeConfirmReceiver" android:exported="false">
//        <intent-filter>
//            <action android:name="com.axiom.axiomnew.ACTION_CONFIRM_MODE"/>
//            <action android:name="com.axiom.axiomnew.ACTION_SKIP_MODE"/>
//        </intent-filter>
//    </receiver>
// ════════════════════════════════════════════════════════════════════════════
class ModeConfirmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CONFIRM = "com.axiom.axiomnew.ACTION_CONFIRM_MODE"
        const val ACTION_SKIP    = "com.axiom.axiomnew.ACTION_SKIP_MODE"
        const val EXTRA_MODE     = "extra_mode"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        when (intent.action) {

            ACTION_CONFIRM -> {
                val mode = intent.getStringExtra(EXTRA_MODE) ?: return
                applyModeNow(context, mode)
                nm.cancel(AxiomService.NOTIF_MODE_ID)
                val label = when (mode) {
                    "SLEEP"   -> "🌙 Sleep mode on"
                    "COMMUTE" -> "🎧 Commute mode on"
                    "WORK"    -> "💼 Work mode on"
                    "FOCUS"   -> "🎯 Focus mode on"
                    "GAMING"  -> "🎮 Gaming mode on"
                    else      -> "✅ Mode switched"
                }
                Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
            }

            ACTION_SKIP -> {
                // Silent dismiss — does not suppress future suggestions
                nm.cancel(AxiomService.NOTIF_MODE_ID)
            }
        }
    }

    // ── Apply the mode directly ───────────────────────────────────────────────
    // Duplicates the core of PhoneModeManager.applyMode() so this receiver
    // has zero dependency on any particular version of PhoneModeManager.
    private fun applyModeNow(context: Context, mode: String) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            when (mode) {
                "SLEEP"  -> am.ringerMode = AudioManager.RINGER_MODE_SILENT
                "WORK"   -> am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                "FOCUS"  -> am.ringerMode = AudioManager.RINGER_MODE_SILENT
                "GAMING" -> {
                    am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
                }
                "COMMUTE" -> {
                    am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.7).toInt(), 0)
                }
                else -> am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
        }
        // Record in prefs so InsightActivity shows the correct current mode
        context.getSharedPreferences("axiom_mode", Context.MODE_PRIVATE)
            .edit()
            .putString("current_mode", mode)
            .putLong("last_switch_ts", System.currentTimeMillis())
            .apply()
    }
}