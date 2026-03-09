
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
import android.widget.Toast

// ════════════════════════════════════════════════════════════════════════════
//  ModeCancelReceiver
//
//  Handles the "Cancel for today" action button on mode notifications.
//  Calls PhoneModeManager.cancelForToday() which:
//    • Suppresses automatic mode switching until midnight
//    • Immediately restores Normal mode
//    • Records the cancel in the mode log
//
//  Register in AndroidManifest.xml:
//    <receiver android:name=".ModeCancelReceiver" android:exported="false">
//        <intent-filter>
//            <action android:name="com.axiom.axiomnew.ACTION_CANCEL_MODE"/>
//        </intent-filter>
//    </receiver>
// ════════════════════════════════════════════════════════════════════════════
class ModeCancelReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PhoneModeManager.ACTION_CANCEL_MODE) return

        PhoneModeManager(context).cancelForToday()

        // Dismiss the mode notification
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(AxiomService.NOTIF_MODE_ID)

        Toast.makeText(context, "Mode paused until midnight", Toast.LENGTH_SHORT).show()
    }
}