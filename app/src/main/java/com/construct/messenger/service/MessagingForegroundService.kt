package com.construct.messenger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.construct.messenger.R
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.repository.AuthRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the persistent, no-GMS message stream while an authenticated identity exists.
 * The service is a lifecycle host only: session decisions remain in CFE and MessagingRuntime.
 */
@AndroidEntryPoint
class MessagingForegroundService : Service() {

    @Inject
    lateinit var cryptoManager: CryptoManager

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var messagingRuntime: MessagingRuntime

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceScope.launch {
            if (messagingRuntime.isStarted) return@launch
            if (cryptoManager.isMessagingReady) {
                messagingRuntime.start()
                return@launch
            }

            // START_STICKY may recreate this service after the process was killed. Restore
            // identity first; AuthRepository's service start is idempotent and the runtime mutex
            // prevents a duplicate stream if the original caller is still finishing startup.
            val restored = runCatching { authRepository.restoreSession() }
                .onFailure { Log.e(TAG, "identity restore from foreground service failed", it) }
                .getOrDefault(false)
            if (restored && !messagingRuntime.isStarted) {
                runCatching { messagingRuntime.start() }
                    .onFailure { Log.e(TAG, "messaging runtime start failed", it) }
            } else if (!restored) {
                // There is no identity to serve. Do not leave a sticky foreground notification
                // alive after an uninstall/cleared-keystore/process-recreation edge case.
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        messagingRuntime.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.messaging_foreground_notification))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.messaging_foreground_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.messaging_foreground_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val TAG = "MessagingFgService"
        const val CHANNEL_ID = "messaging_connection"
        const val NOTIFICATION_ID = 1001
    }
}
