package com.construct.messenger

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.SystemBarStyle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
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
import com.construct.messenger.data.repository.AppLockRepository
import com.construct.messenger.data.repository.AppearanceRepository
import com.construct.messenger.ui.components.SecurityNoticeHost
import com.construct.messenger.ui.navigation.Screen
import com.construct.messenger.data.local.PendingInviteStore
import com.construct.messenger.service.MessageNotifier
import com.construct.messenger.ui.navigation.KonstructNavHost
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.screens.security.PinLockScreen
import com.construct.messenger.ui.theme.ChatText
import com.construct.messenger.ui.theme.KonstructMessengerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

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

    @Inject
    lateinit var appLock: AppLockRepository

    @Inject
    lateinit var calls: com.construct.messenger.data.repository.CallsRepository

    @Inject
    lateinit var veilConfig: com.construct.messenger.veil.VeilConfigImporter

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
                    // An invite tapped while a screen is open above the tabs — a link in a chat:
                    // back to the tabs, where Synaps redeems it. Before the tabs exist (cold
                    // start, onboarding) it waits for them.
                    val invite by pendingInvites.pending.collectAsStateWithLifecycle()
                    LaunchedEffect(invite) {
                        if (invite != null && runCatching { navController.getBackStackEntry(Screen.Main.route) }.isSuccess) {
                            navController.popBackStack(Screen.Main.route, inclusive = false)
                        }
                    }
                    val call by calls.call.collectAsStateWithLifecycle()
                    // A new call — placed from a chat, or ringing while the app is open — opens the
                    // call screen; minimising it leaves the strip, which opens it again.
                    LaunchedEffect(call?.callId) { if (call?.isLive == true) CallActivity.open(this@MainActivity) }
                    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                        val live = call?.takeIf { it.isLive }
                        if (live != null) {
                            com.construct.messenger.ui.screens.calls.CallMiniBar(live) { CallActivity.open(this@MainActivity) }
                        }
                        androidx.compose.foundation.layout.Box(
                            Modifier.weight(1f).then(
                                // The strip already sits under the status bar; the screens below must not pad for it twice.
                                if (live != null) {
                                    Modifier.consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.statusBars)
                                } else {
                                    Modifier
                                },
                            ),
                        ) {
                            KonstructNavHost(navController = navController)
                        }
                    }
                    SecurityNoticeHost(
                        onOpenChat = { navController.navigate(Screen.Chat.createRoute(it)) },
                    )
                    // iOS `SecurityGateView`: the lock covers everything while the PIN is required.
                    val lock by appLock.lock.collectAsStateWithLifecycle()
                    LaunchedEffect(lock.pinEnabled) {
                        // With a PIN, the recents thumbnail must not show what the lock hides.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            setRecentsScreenshotEnabled(!lock.pinEnabled)
                        }
                    }
                    if (lock.requiresUnlock) PinLockScreen()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appLock.onForeground()
    }

    override fun onStop() {
        super.onStop()
        appLock.onBackground()
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
        if (com.construct.messenger.invite.InviteConfig.isInviteLink(data)) {
            pendingInvites.offer(data)
        } else if (com.construct.messenger.veil.VeilConfigLink.isLink(data)) {
            // Said either way: a tapped link that does nothing visible reads as a broken link.
            val outcome = veilConfig.redeem(data)
            android.widget.Toast.makeText(
                this,
                com.construct.messenger.veil.VeilConfigImporter.messageOf(outcome),
                android.widget.Toast.LENGTH_LONG,
            ).show()
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
