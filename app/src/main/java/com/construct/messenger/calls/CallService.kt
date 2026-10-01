package com.construct.messenger.calls

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.construct.messenger.diagnostics.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps a call alive with the screen off and the app in the background: a foreground service of
 * type `phoneCall|microphone`, its notification the ongoing call. Android stops giving an app the
 * microphone once it leaves the screen unless a service like this holds it.
 *
 * Also takes the notification's own buttons — decline and hang up — which reach the app as
 * service intents, not broadcasts (the app has no `<receiver>`).
 */
@AndroidEntryPoint
class CallService : Service() {

    @Inject lateinit var calls: CallManager
    @Inject lateinit var notifications: CallNotifications

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DECLINE, ACTION_HANGUP -> {
                Log.i(TAG, "${intent.action} from the notification")
                calls.end()
                if (!foreground) stopSelf(startId)
            }
            ACTION_START -> if (!foreground) start()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        // A service started as foreground must go foreground even if the call ended on the way.
        val session = calls.state.value.session()
            ?: CallSession("", "", getString(com.construct.messenger.R.string.app_name), CallSession.Direction.OUTGOING)
        startInForeground(notifications.ongoing(session, null))
        foreground = true
        scope.launch {
            calls.state.collect { state ->
                when (state) {
                    is CallState.Ended, CallState.Idle -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    else -> state.session()?.let { s ->
                        val since = (state as? CallState.Active)?.sinceMs
                        runCatching {
                            getSystemService(android.app.NotificationManager::class.java)
                                .notify(CallNotifications.ID_ONGOING, notifications.ongoing(s, since))
                        }
                    }
                }
            }
        }
    }

    /**
     * Microphone as well as phone call: without it the far side hears silence once the screen goes
     * off. Started with the microphone type only while the app is on screen (the user just called or
     * answered); if the system refuses it — an answer from a headset button in the background —
     * phone call alone keeps the call, and the microphone follows when the app is opened.
     */
    private fun startInForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val both = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            try {
                startForeground(CallNotifications.ID_ONGOING, notification, both)
            } catch (e: Exception) {
                Log.w(TAG, "microphone foreground refused (${e.message}) — phone call only")
                startForeground(CallNotifications.ID_ONGOING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(CallNotifications.ID_ONGOING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(CallNotifications.ID_ONGOING, notification)
        }
        Log.i(TAG, "call foreground started")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CallService"
        const val ACTION_START = "com.construct.messenger.call.START"
        const val ACTION_DECLINE = "com.construct.messenger.call.DECLINE"
        const val ACTION_HANGUP = "com.construct.messenger.call.HANGUP"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CallService::class.java).setAction(ACTION_START)) }
                .onFailure { Log.w(TAG, "call service not started: ${it.message}") }
        }
    }
}

/** The call a state is about, if any. */
fun CallState.session(): CallSession? = when (this) {
    CallState.Idle -> null
    is CallState.Incoming -> session
    is CallState.Dialing -> session
    is CallState.Ringing -> session
    is CallState.Connecting -> session
    is CallState.Active -> session
    is CallState.Ended -> session
}
