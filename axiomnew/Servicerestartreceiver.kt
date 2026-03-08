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