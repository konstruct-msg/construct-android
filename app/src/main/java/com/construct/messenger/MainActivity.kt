package com.construct.messenger

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.navigation.compose.rememberNavController
import com.construct.messenger.data.local.PendingInviteStore
import com.construct.messenger.ui.navigation.KonstructNavHost
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.KonstructMessengerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var pendingInvites: PendingInviteStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureInvite(intent)
        val barScrim = CTColor.bg.toArgb()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(barScrim),
            navigationBarStyle = SystemBarStyle.dark(barScrim),
        )
        setContent {
            KonstructMessengerTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = CTColor.bg,
                ) {
                    val navController = rememberNavController()
                    KonstructNavHost(navController = navController)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureInvite(intent)
    }

    private fun captureInvite(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (data.startsWith("konstruct://add")) {
            pendingInvites.offer(data)
        }
    }
}
