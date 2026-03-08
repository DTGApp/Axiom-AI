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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

// ════════════════════════════════════════════════════════════════════════════
//  ServiceRestartReceiver
//
//  Receives the alarm scheduled by AxiomService.scheduleRestart() and
//  AxiomService.onTaskRemoved(). Restarts AxiomService as a foreground
//  service, even if the app process was killed by the OS or by MIUI's
//  aggressive battery optimisation.
//
//  Registration in AndroidManifest.xml (REQUIRED):
//
//    <receiver
//        android:name=".ServiceRestartReceiver"
//        android:exported="false"/>
//
//  The SCHEDULE_EXACT_ALARM permission is required on Android 12+ for
//  setExactAndAllowWhileIdle. Add to manifest:
//    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM"/>
//
//  On MIUI specifically, also guide the user to:
//    Settings → Apps → Axiom → Battery Saver → No restrictions
//    Settings → Apps → Axiom → Autostart → Enable
// ════════════════════════════════════════════════════════════════════════════
class ServiceRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.i("AxiomRestart", "ServiceRestartReceiver fired — restarting AxiomService")

        val svcIntent = Intent(context, AxiomService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svcIntent)
            } else {
                context.startService(svcIntent)
            }
            android.util.Log.i("AxiomRestart", "AxiomService restart command sent ✓")
        } catch (e: Exception) {
            android.util.Log.e("AxiomRestart", "Restart failed: ${e.message}")
        }
    }
}