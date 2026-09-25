package com.construct.messenger.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.construct.messenger.MainActivity
import com.construct.messenger.R
import com.construct.messenger.data.local.ChatPresence
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What [ProcessorEffectsImpl] needs to know about the user when a message lands. */
interface IncomingAlerts {
    /** The chat is on screen: the message is read as it lands — no unread, no banner. */
    fun isChatVisible(contactId: String): Boolean

    /** A message nobody has seen yet arrived in this chat. */
    fun onUnseenMessage(contactId: String)

    /** The chat was opened: its banner has done its job. */
    fun clear(contactId: String)
}

/**
 * Local notification for an incoming message.
 *
 * **Canon:** iOS `InAppNotificationService` → `LocalNotificationManager.showNewMessageNotification`.
 * Privacy: always the app name and "New message" — no sender, no text. The notification shade,
 * the lock screen and anything mirroring notifications (a watch, a desktop companion) are
 * outside the encryption; what is not put there cannot leak from there.
 * One notification per chat: repeats replace it instead of stacking up.
 *
 * Posted from the process that decrypted the message — the foreground stream — so it needs no
 * push provider (AGENTS.md, no GMS).
 */
@Singleton
class MessageNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val presence: ChatPresence,
) : IncomingAlerts {

    override fun isChatVisible(contactId: String): Boolean = presence.isVisible(contactId)

    override fun onUnseenMessage(contactId: String) {
        if (!canPost()) return
        ensureChannel()
        val open = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_CHAT)
            .putExtra(EXTRA_CONTACT_ID, contactId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(
            context,
            contactId.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_app_name))
            .setContentText(context.getString(R.string.notification_new_message))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // The generic text is the public version too: nothing more to hide on a locked screen.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(contactId, NOTIFICATION_ID, notification)
    }

    override fun clear(contactId: String) {
        NotificationManagerCompat.from(context).cancel(contactId, NOTIFICATION_ID)
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_messages),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notification_channel_messages_description)
            },
        )
    }

    companion object {
        const val ACTION_OPEN_CHAT = "com.construct.messenger.OPEN_CHAT"
        const val EXTRA_CONTACT_ID = "contact_id"
        private const val CHANNEL_ID = "messages"
        // Tagged by contact id: one slot per chat, and a different id from the service's 1001.
        private const val NOTIFICATION_ID = 2001
    }
}
