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
    /** Settings → profile row: alias, fingerprint, account id, sign out. */
    data object Account : Screen("settings/account")
    data object Security : Screen("settings/security")
    /** My invite: self-refreshing QR + copy link (iOS ContactQRCodeView). */
    data object InviteQr : Screen("invite_qr")
    /** Camera scanner; a scanned invite is redeemed on the Synaps tab. */
    data object ScanQr : Screen("scan_qr")
}