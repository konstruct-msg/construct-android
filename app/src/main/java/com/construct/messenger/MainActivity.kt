package com.construct.messenger

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.construct.messenger.data.local.PendingChatStore
import com.construct.messenger.data.model.AppTheme
import com.construct.messenger.data.model.ChatFace
import com.construct.messenger.data.repository.AppearanceRepository
import com.construct.messenger.ui.components.SecurityNoticeHost
import com.construct.messenger.ui.navigation.Screen
import com.construct.messenger.data.local.PendingInviteStore
import com.construct.messenger.service.MessageNotifier
import com.construct.messenger.ui.navigation.KonstructNavHost
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.ChatText
import com.construct.messenger.ui.theme.KonstructMessengerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            // The foreground service still keeps the stream alive; Android may hide its
            // drawer notification until the user enables notifications in system settings.
        }
    }

    @Inject
    lateinit var pendingInvites: PendingInviteStore

    @Inject
    lateinit var pendingChats: PendingChatStore

    @Inject
    lateinit var appearanceRepository: AppearanceRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        captureInvite(intent)
        captureChat(intent)
        setContent {
            val appearance by appearanceRepository.appearance.collectAsStateWithLifecycle()
            val darkTheme = when (appearance.theme) {
                AppTheme.DARK -> true
                AppTheme.LIGHT -> false
                AppTheme.AUTOMATIC -> isSystemInDarkTheme()
            }
            LaunchedEffect(darkTheme) { applySystemBars(darkTheme) }
            KonstructMessengerTheme(
                darkTheme = darkTheme,
                chatText = ChatText(
                    monospace = appearance.chatFace == ChatFace.MONO,
                    multiplier = appearance.textSize.multiplier,
                ),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = CTColor.bg,
                ) {
                    val navController = rememberNavController()
                    KonstructNavHost(navController = navController)
                    SecurityNoticeHost(
                        onOpenChat = { navController.navigate(Screen.Chat.createRoute(it)) },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureInvite(intent)
        captureChat(intent)
    }

    /** A tapped message notification: open that chat once the tabs are up. */
    private fun captureChat(intent: Intent?) {
        if (intent?.action != MessageNotifier.ACTION_OPEN_CHAT) return
        intent.getStringExtra(MessageNotifier.EXTRA_CONTACT_ID)?.let(pendingChats::offer)
    }

    private fun captureInvite(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (data.startsWith("konstruct://add")) {
            pendingInvites.offer(data)
        }
    }

    /** Bars take the app background of the chosen theme, with icons that read on it. */
    private fun applySystemBars(darkTheme: Boolean) {
        val style = if (darkTheme) {
            SystemBarStyle.dark(CTColor.bgDark.toArgb())
        } else {
            SystemBarStyle.light(CTColor.bgLight.toArgb(), CTColor.bgDark.toArgb())
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
