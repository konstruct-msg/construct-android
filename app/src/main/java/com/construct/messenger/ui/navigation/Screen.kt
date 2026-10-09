package com.construct.messenger.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Onboarding : Screen("onboarding")
    /** Sign in to an existing account with its recovery phrase. */
    data object ExistingIdentity : Screen("existing_identity")
    data object Restore : Screen("restore")

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
    /** A contact's profile: identity, safety numbers, block / report / remove. */
    data object Contact : Screen("contact/{contactId}?fromChat={fromChat}") {
        fun createRoute(contactId: String, fromChat: Boolean = false) =
            "contact/${Uri.encode(contactId)}?fromChat=$fromChat"
    }
    /** Safety numbers with a contact, one per device of theirs. */
    data object SafetyNumbers : Screen("safety/{contactId}") {
        fun createRoute(contactId: String) = "safety/${Uri.encode(contactId)}"
    }
    /** Settings → profile row: alias, fingerprint, account id, sign out. */
    data object Account : Screen("settings/account")
    /** The account's devices: list, revoke, sign out (iOS `DevicesView`). */
    data object Devices : Screen("settings/devices")
    /** The app's switch, the system permission, channels, delivery (iOS `NotificationsSettingsView`). */
    data object Notifications : Screen("settings/notifications")

    data object DataStorage : Screen("settings/storage")
    /** Local notes, never sent (iOS `DraftsView`). */
    data object Drafts : Screen("settings/drafts")
    /** Theme, message face and size (iOS `AppearanceSettingsView`). */
    data object Appearance : Screen("settings/appearance")
    data object Security : Screen("settings/security")
    /** Create / change / turn off the app-lock PIN (iOS `PinSetupView`, `PinDisableView`). */
    data object PinSetup : Screen("settings/security/pin/{flow}") {
        fun createRoute(flow: String) = "settings/security/pin/$flow"
    }
    /** Invites this device issued and can still revoke (iOS `IssuedInvitesView`). */
    data object IssuedInvites : Screen("settings/security/invites")
    /** Stream status, server, transport (iOS `NetworkSettingsView`, without VEIL). */
    data object Network : Screen("settings/network")
    /** Logs: share, clear, the last lines. Debug builds only (iOS `DiagnosticsView`). */
    data object Diagnostics : Screen("settings/diagnostics")
    /** The recovery phrase, from the Settings banner; returns when the device knows the address. */
    data object RecoverySetup : Screen("settings/recovery")
    /** My invite: self-refreshing QR + copy link (iOS ContactQRCodeView). */
    data object InviteQr : Screen("invite_qr")
    /** Camera scanner; a scanned invite is redeemed on the Synaps tab. */
    data object ScanQr : Screen("scan_qr")
    /** The recovery phrase, offered once after the first orientation (iOS `RecoveryGateView`). */
    data object RecoveryPrompt : Screen("recovery_prompt")
}