package com.construct.messenger.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.construct.messenger.CallActivity
import com.construct.messenger.R
import com.construct.messenger.diagnostics.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two notifications of a call: the ringing one, and the one that stays while the call is on.
 * **Canon:** what CallKit draws on iOS — Android has no system call screen for a self-managed
 * call, so the app posts it.
 *
 * The ringing one opens [CallActivity] over the lock screen (full-screen intent) and rings with the
 * ringtone until answered or gone. On the lock screen the caller's name is not shown — the public
 * version says only that a call is coming, as message notifications say only that a message came;
 * the call screen itself names the caller, as CallKit does.
 */
@Singleton
class CallNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        if (system.getNotificationChannel(CHANNEL_INCOMING) == null) {
            system.createNotificationChannel(
                NotificationChannel(CHANNEL_INCOMING, context.getString(R.string.call_channel_incoming), NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    )
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            )
        }
        if (system.getNotificationChannel(CHANNEL_ONGOING) == null) {
            system.createNotificationChannel(
                NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.call_channel_ongoing), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
    }

    /** Ring: heads-up or the full call screen, with answer and decline. */
    fun showIncoming(session: CallSession) {
        ensureChannels()
        val fullScreen = activity(CallActivity.ACTION_SHOW, REQUEST_SHOW)
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_app_name))
            .setContentText(context.getString(R.string.call_incoming_audio))
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText(context.getString(R.string.call_incoming_audio))
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    person(session),
                    service(CallService.ACTION_DECLINE, REQUEST_DECLINE),
                    activity(CallActivity.ACTION_ANSWER, REQUEST_ANSWER),
                ),
            )
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
            // The ringtone repeats until the notification goes, not once.
            .apply { flags = flags or Notification.FLAG_INSISTENT }
        notify(ID_INCOMING, notification)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) {
            Log.w(TAG, "full-screen intent not allowed — the call rings as a heads-up only")
        }
    }

    fun cancelIncoming() = manager.cancel(ID_INCOMING)

    /** For [CallService]'s foreground: who, since when, and hang up. */
    fun ongoing(session: CallSession, activeSinceMs: Long?): Notification =
        NotificationCompat.Builder(context, CHANNEL_ONGOING.also { ensureChannels() })
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentText(context.getString(if (activeSinceMs != null) R.string.call_status_connected else R.string.call_status_calling))
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(session), service(CallService.ACTION_HANGUP, REQUEST_HANGUP)))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(activity(CallActivity.ACTION_SHOW, REQUEST_SHOW))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (activeSinceMs != null) setWhen(activeSinceMs).setUsesChronometer(true).setShowWhen(true) else setShowWhen(false)
            }
            .build()

    private fun person(session: CallSession) = Person.Builder().setName(session.peerName).setImportant(true).build()

    private fun activity(action: String, request: Int) = PendingIntent.getActivity(
        context,
        request,
        Intent(context, CallActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun service(action: String, request: Int) = PendingIntent.getService(
        context,
        request,
        Intent(context, CallService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @android.annotation.SuppressLint("MissingPermission") // notify() is a no-op without POST_NOTIFICATIONS
    private fun notify(id: Int, notification: Notification) {
        runCatching { manager.notify(id, notification) }.onFailure { Log.w(TAG, "call notification not posted: ${it.message}") }
    }

    companion object {
        private const val TAG = "CallNotify"
        const val CHANNEL_INCOMING = "calls_incoming"
        const val CHANNEL_ONGOING = "calls_ongoing"
        const val ID_INCOMING = 2001
        const val ID_ONGOING = 2002
        private const val REQUEST_SHOW = 1
        private const val REQUEST_ANSWER = 2
        private const val REQUEST_DECLINE = 3
        private const val REQUEST_HANGUP = 4
    }
}
