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