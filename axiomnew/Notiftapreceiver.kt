package com.axiom.axiomnew

import android.content.*
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import java.io.File

// ════════════════════════════════════════════════════════════════════════════════
//  NotifTapReceiver
//
//  Handles taps and dismissals on proactive Axiom notifications.
//
//  Tap  → launch the real app (Spotify, Maps, etc.) if installed,
//          fallback to Settings screen, register feedback(1)
//  Swipe / "Not now" → register feedback(0), no launch
//
//  If the app process was killed when this fires, feedback is written to
//  axiom_pending_feedback.jsonl and replayed when MainActivity next opens.
// ════════════════════════════════════════════════════════════════════════════════
class NotifTapReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_ACTION         = "axiom_action"
        const val EXTRA_ANDROID_ACTION = "axiom_android_action"
        const val EXTRA_ACCEPTED       = "axiom_accepted"
        const val PENDING_FB_FILE      = "axiom_pending_feedback.jsonl"

        init { System.loadLibrary("axiom_engine") }

        // Mirror of intentFor()'s app-launch priorities.
        // Kept here so NotifTapReceiver can launch apps without needing MainActivity.
        private val APP_LAUNCH_PRIORITY = mapOf(
            "ACTION_ALARM"              to listOf("com.google.android.deskclock",
                "com.sec.android.app.clockpackage",
                "com.miui.clock",
                "com.oneplus.clock",
                "com.android.deskclock"),
            "ACTION_SOUND_SETTINGS"     to listOf("com.spotify.music",
                "com.google.android.music",
                "com.apple.android.music",
                "com.amazon.mp3",
                "com.miui.player",
                "com.sec.android.app.music"),
            "ACTION_MEDIA_VOLUME"       to listOf("com.spotify.music",
                "com.google.android.music",
                "com.apple.android.music"),
            "ACTION_LOCATION_SETTINGS"  to listOf("com.google.android.apps.maps",
                "com.waze", "com.here.app.maps",
                "com.ubercab"),
            "ACTION_CAMERA_SETTINGS"    to listOf("com.google.android.GoogleCamera",
                "com.sec.android.app.camera",
                "com.miui.camera",
                "com.oneplus.camera"),
            "ACTION_PHOTO_QUALITY"      to listOf("com.google.android.GoogleCamera",
                "com.sec.android.app.camera",
                "com.miui.camera"),
            "ACTION_VIDEO_SETTINGS"     to listOf("com.google.android.GoogleCamera",
                "com.sec.android.app.camera",
                "com.miui.camera"),
            "ACTION_GALLERY"            to listOf("com.google.android.apps.photos",
                "com.miui.gallery",
                "com.sec.android.gallery3d"),
            "ACTION_VPN"                to listOf("com.nordvpn.android",
                "com.expressvpn.vpn",
                "com.protonvpn.android",
                "com.privateinternetaccess.android"),
            "ACTION_FOCUS_MODE"         to listOf("com.forestapp.Forest",
                "com.onetouchapp.one"),
            "ACTION_DND"                to listOf("com.forestapp.Forest",
                "com.onetouchapp.one"),
            "ACTION_SLEEP_SETTINGS"     to listOf("com.urbandroid.sleep",
                "com.relaxio.calmsleep"),
            "ACTION_STRESS_MONITOR"     to listOf("com.calm.android",
                "com.headspace.android",
                "com.endel.endel"),
            "ACTION_HEALTH_SETTINGS"    to listOf("com.google.android.apps.fitness",
                "com.samsung.shealth",
                "com.xiaomi.hm.health"),
            "ACTION_STEPS_COUNTER"      to listOf("com.google.android.apps.fitness",
                "com.fitbit.FitbitMobile",
                "com.garmin.android.apps.connectmobile",
                "com.strava"),
            "ACTION_GAME_MODE"          to listOf("com.samsung.android.game.gamehome",
                "com.miui.gamebooster"),
            "ACTION_DIGITAL_WELLBEING"  to listOf("com.google.android.apps.wellbeing",
                "com.samsung.android.forest"),
            "ACTION_SMART_HOME"         to listOf("io.homeassistant.companion.android",
                "com.amazon.echo",
                "com.xiaomi.smarthome",
                "com.google.android.apps.chromecast.app"),
            "ACTION_ROUTINES"           to listOf("com.samsung.android.app.routines",
                "net.dinglisch.android.taskerm"),
            "ACTION_CAST"               to listOf("com.google.android.apps.chromecast.app",
                "com.sec.android.smartmirroring"),
            "ACTION_TWO_FACTOR_AUTH"    to listOf("com.google.android.apps.authenticator2",
                "com.authy.authy"),
            "ACTION_SECURITY_SETTINGS"  to listOf("com.bitwarden.mobile",
                "com.onepassword.android"),
            "ACTION_PRIVACY_SETTINGS"   to listOf("org.thoughtcrime.securesms",
                "org.telegram.messenger"),
            "ACTION_NOTIFICATION_SETTINGS" to listOf("com.microsoft.teams",
                "com.slack", "com.discord"),
            "ACTION_CALENDAR_SETTINGS"  to listOf("com.google.android.calendar",
                "com.microsoft.office.outlook"),
            "ACTION_DATE_TIME"          to listOf("com.google.android.calendar",
                "com.todoist.android.Todoist"),
            "ACTION_BLUETOOTH_SETTINGS" to listOf("com.samsung.android.app.galaxybuds",
                "com.sony.songpal.mdrconnect",
                "com.bose.bosemusic"),
        )
    }

    private external fun nativeRegisterFeedback(intent: String, accepted: Int)

    override fun onReceive(context: Context, intent: Intent) {
        val action        = intent.getStringExtra(EXTRA_ACTION)         ?: return
        val androidAction = intent.getStringExtra(EXTRA_ANDROID_ACTION) ?: ""
        val accepted      = intent.getBooleanExtra(EXTRA_ACCEPTED, false)

        android.util.Log.i("AxiomNotif",
            "Notification: action=$action accepted=$accepted")

        // Register feedback — try JNI first, fall back to file if process killed
        try {
            nativeRegisterFeedback(action, if (accepted) 1 else 0)
        } catch (e: UnsatisfiedLinkError) {
            writePendingFeedback(context, action, if (accepted) 1 else 0)
        }

        // Launch on tap
        if (accepted) {
            launchBestIntent(context, action, androidAction)
        }

        NotificationManagerCompat.from(context).cancel(MainActivity.NOTIF_ID)
    }

    // Try installed apps first, then Settings action string, then generic Settings
    private fun launchBestIntent(context: Context, action: String, androidAction: String) {
        val pm = context.packageManager

        // 1. Try known app packages for this intent
        val candidates = APP_LAUNCH_PRIORITY[action]
        if (candidates != null) {
            for (pkg in candidates) {
                val launch = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(launch) }.onSuccess {
                        android.util.Log.i("AxiomNotif", "Launched app: $pkg for $action")
                        return
                    }
                }
            }
        }

        // 2. Fall back to the Settings action string
        if (androidAction.isNotEmpty()) {
            runCatching {
                context.startActivity(
                    Intent(androidAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                android.util.Log.i("AxiomNotif", "Launched Settings: $androidAction")
                return
            }
        }

        // 3. Last resort — generic Settings
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        android.util.Log.w("AxiomNotif", "Fell back to generic Settings for $action")
    }

    private fun writePendingFeedback(context: Context, action: String, accepted: Int) {
        runCatching {
            val file = File(context.filesDir, PENDING_FB_FILE)
            val ts   = System.currentTimeMillis() / 1000
            file.appendText("{\"action\":\"$action\",\"accepted\":$accepted,\"ts\":$ts}\n")
        }
    }
}