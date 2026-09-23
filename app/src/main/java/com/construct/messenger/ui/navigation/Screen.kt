package com.construct.messenger.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Onboarding : Screen("onboarding")

    /** Post-registration product guide. [fromSettings] = replay (returns back instead of Main). */
    data object Orientation : Screen("orientation?fromSettings={fromSettings}") {
        fun createRoute(fromSettings: Boolean = false) = "orientation?fromSettings=$fromSettings"
    }

    /** [startTab] selects the initial tab (0 = Chats … 1 = Synaps). */
    data object Main : Screen("main?startTab={startTab}") {
        fun createRoute(startTab: Int = 0) = "main?startTab=$startTab"
    }
    data object Chat : Screen("chat/{contactId}") {
        fun createRoute(contactId: String) = "chat/${Uri.encode(contactId)}"
    }
    data object Settings : Screen("settings")
}