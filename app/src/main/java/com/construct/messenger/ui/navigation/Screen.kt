package com.construct.messenger.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Onboarding : Screen("onboarding")
    data object Main : Screen("main")
    data object Chat : Screen("chat/{contactId}") {
        fun createRoute(contactId: String) = "chat/${Uri.encode(contactId)}"
    }
    data object Settings : Screen("settings")
}