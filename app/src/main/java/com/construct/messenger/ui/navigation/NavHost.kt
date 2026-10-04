package com.construct.messenger.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.construct.messenger.ui.screens.chat.ChatScreen
import com.construct.messenger.ui.screens.chat.SafetyNumberScreen
import com.construct.messenger.ui.screens.onboarding.RestoreAccountScreen
import com.construct.messenger.ui.screens.synaps.ContactProfileScreen
import com.construct.messenger.ui.screens.invite.ContactQrScreen
import com.construct.messenger.ui.screens.invite.QrScannerScreen
import androidx.hilt.navigation.compose.hiltViewModel
import com.construct.messenger.recovery.RecoveryKeyLaunchViewModel
import com.construct.messenger.ui.screens.recovery.RecoveryGated
import com.construct.messenger.ui.screens.recovery.RecoveryPromptScreen
import com.construct.messenger.ui.screens.recovery.RecoverySetupScreen
import com.construct.messenger.ui.screens.main.MainTabView
import com.construct.messenger.ui.screens.onboarding.ExistingIdentityScreen
import com.construct.messenger.ui.screens.onboarding.OnboardingScreen
import com.construct.messenger.ui.screens.orientation.OrientationScreen
import com.construct.messenger.ui.screens.security.PinFlow
import com.construct.messenger.ui.screens.security.PinSetupScreen
import com.construct.messenger.ui.screens.settings.AccountScreen
import com.construct.messenger.ui.screens.settings.AppearanceRoute
import com.construct.messenger.ui.screens.settings.DevicesRoute
import com.construct.messenger.ui.screens.settings.DraftsRoute
import com.construct.messenger.ui.screens.settings.IssuedInvitesRoute
import com.construct.messenger.ui.screens.settings.DiagnosticsScreen
import com.construct.messenger.ui.screens.settings.NetworkScreen
import com.construct.messenger.ui.screens.settings.NotificationsRoute
import com.construct.messenger.ui.screens.settings.SecurityScreen
import com.construct.messenger.ui.screens.settings.SettingsNavigation
import com.construct.messenger.ui.screens.splash.SplashScreen

/** Tab index Orientation lands on when finished (Synaps — "start in Synaps"). */
private const val TAB_SYNAPS = 1

@Composable
fun KonstructNavHost(
    navController: NavHostController,
    startDestination: String = Screen.Splash.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Splash.route) {
            SplashScreen(
                onNavigateToOnboarding = {
                    navController.navigate(Screen.Onboarding.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateToOrientation = {
                    navController.navigate(Screen.Orientation.createRoute()) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateToMain = {
                    navController.navigate(Screen.Main.createRoute()) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onInitialized = {
                    // First run: registration flows straight into the product guide.
                    navController.navigate(Screen.Orientation.createRoute()) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onExistingIdentity = {
                    navController.navigate(Screen.ExistingIdentity.route) { launchSingleTop = true }
                },
            )
        }
        composable(Screen.ExistingIdentity.route) {
            ExistingIdentityScreen(
                onBack = { navController.popBackStack() },
                onRestore = { navController.navigate(Screen.Restore.route) { launchSingleTop = true } },
            )
        }
        composable(Screen.Restore.route) {
            RestoreAccountScreen(
                onBack = { navController.popBackStack() },
                onRestored = {
                    // An existing account: no product guide, straight to its chats.
                    navController.navigate(Screen.Main.createRoute()) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(
            route = Screen.Orientation.route,
            arguments = listOf(
                navArgument("fromSettings") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val fromSettings = backStackEntry.arguments?.getBoolean("fromSettings") ?: false
            OrientationScreen(
                onFinished = {
                    if (fromSettings) {
                        navController.popBackStack()
                    } else {
                        // Registration stays one step. The recovery key is made here without a
                        // screen; the setup shows only when it could not be (no screen lock).
                        navController.navigate(Screen.RecoveryPrompt.route) {
                            popUpTo(Screen.Orientation.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
            )
        }
        composable(
            route = Screen.Main.route,
            arguments = listOf(
                navArgument("startTab") {
                    type = NavType.IntType
                    defaultValue = 0
                }
            )
        ) { backStackEntry ->
            // Every launch of a signed-in account makes the recovery key if it is missing.
            hiltViewModel<RecoveryKeyLaunchViewModel>()
            MainTabView(
                startTab = backStackEntry.arguments?.getInt("startTab") ?: 0,
                onNavigateToChat = { contactId ->
                    navController.navigate(Screen.Chat.createRoute(contactId)) {
                        launchSingleTop = true
                    }
                },
                onOpenContact = { contactId ->
                    navController.navigate(Screen.Contact.createRoute(contactId)) { launchSingleTop = true }
                },
                onScanQr = { navController.navigate(Screen.ScanQr.route) { launchSingleTop = true } },
                onShowMyQr = { navController.navigate(Screen.InviteQr.route) { launchSingleTop = true } },
                settingsNavigation = SettingsNavigation(
                    onAccount = { navController.navigate(Screen.Account.route) { launchSingleTop = true } },
                    onInvite = { navController.navigate(Screen.InviteQr.route) { launchSingleTop = true } },
                    onDevices = { navController.navigate(Screen.Devices.route) { launchSingleTop = true } },
                    onAppearance = { navController.navigate(Screen.Appearance.route) { launchSingleTop = true } },
                    onSecurity = { navController.navigate(Screen.Security.route) { launchSingleTop = true } },
                    onNotifications = { navController.navigate(Screen.Notifications.route) { launchSingleTop = true } },
                    onDrafts = { navController.navigate(Screen.Drafts.route) { launchSingleTop = true } },
                    onNetwork = { navController.navigate(Screen.Network.route) { launchSingleTop = true } },
                    onDiagnostics = { navController.navigate(Screen.Diagnostics.route) { launchSingleTop = true } },
                    onRecoverySetup = { navController.navigate(Screen.RecoverySetup.route) { launchSingleTop = true } },
                    onOrientation = {
                        navController.navigate(Screen.Orientation.createRoute(fromSettings = true)) {
                            launchSingleTop = true
                        }
                    },
                ),
            )
        }
        composable(Screen.RecoveryPrompt.route) {
            RecoveryPromptScreen(
                onDone = {
                    navController.navigate(Screen.Main.createRoute(startTab = TAB_SYNAPS)) {
                        popUpTo(Screen.RecoveryPrompt.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        // Both invite surfaces need the account's address. Since the silent key (2026-10-04) the
        // device usually knows it already; the gate stays for one that does not — no screen lock
        // at registration, or a key set on another device.
        composable(Screen.InviteQr.route) {
            RecoveryGated(onBack = { navController.popBackStack() }) {
                ContactQrScreen(onNavigateBack = { navController.popBackStack() })
            }
        }
        composable(Screen.ScanQr.route) {
            RecoveryGated(onBack = { navController.popBackStack() }) {
                QrScannerScreen(
                    onNavigateBack = { navController.popBackStack() },
                    // The invite is waiting in PendingInviteStore; the Synaps tab redeems it and
                    // shows the result, as for a tapped konstruct://add link.
                    onScanned = {
                        navController.navigate(Screen.Main.createRoute(startTab = TAB_SYNAPS)) {
                            popUpTo(Screen.Main.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument("contactId") { type = NavType.StringType })
        ) { backStackEntry ->
            ChatScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenSafetyNumbers = {
                    backStackEntry.arguments?.getString("contactId")?.let {
                        navController.navigate(Screen.SafetyNumbers.createRoute(it))
                    }
                },
                onOpenProfile = {
                    backStackEntry.arguments?.getString("contactId")?.let {
                        navController.navigate(Screen.Contact.createRoute(it, fromChat = true)) { launchSingleTop = true }
                    }
                },
            )
        }
        composable(
            route = Screen.Contact.route,
            arguments = listOf(
                navArgument("contactId") { type = NavType.StringType },
                navArgument("fromChat") { type = NavType.BoolType; defaultValue = false },
            ),
        ) {
            ContactProfileScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenChat = { contactId ->
                    navController.navigate(Screen.Chat.createRoute(contactId)) { launchSingleTop = true }
                },
                onOpenSafetyNumbers = { contactId ->
                    navController.navigate(Screen.SafetyNumbers.createRoute(contactId))
                },
            )
        }
        composable(
            route = Screen.SafetyNumbers.route,
            arguments = listOf(navArgument("contactId") { type = NavType.StringType }),
        ) {
            SafetyNumberScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.Account.route) {
            AccountScreen(
                onNavigateBack = { navController.popBackStack() },
                onDevices = { navController.navigate(Screen.Devices.route) { launchSingleTop = true } },
                onRecoverySetup = { navController.navigate(Screen.RecoverySetup.route) { launchSingleTop = true } },
                onSignedOut = {
                    navController.navigate(Screen.Onboarding.route) {
                        popUpTo(Screen.Main.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Screen.Devices.route) {
            DevicesRoute(
                onNavigateBack = { navController.popBackStack() },
                onSignedOut = {
                    navController.navigate(Screen.Onboarding.route) {
                        popUpTo(Screen.Main.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Screen.Notifications.route) {
            NotificationsRoute(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.Drafts.route) {
            DraftsRoute(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.Appearance.route) {
            AppearanceRoute(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.Security.route) {
            SecurityScreen(
                onNavigateBack = { navController.popBackStack() },
                onRecovery = { navController.navigate(Screen.RecoverySetup.route) { launchSingleTop = true } },
                onIssuedInvites = { navController.navigate(Screen.IssuedInvites.route) { launchSingleTop = true } },
                onPin = { flow -> navController.navigate(Screen.PinSetup.createRoute(flow.name)) { launchSingleTop = true } },
            )
        }
        composable(
            route = Screen.PinSetup.route,
            arguments = listOf(navArgument("flow") { type = NavType.StringType }),
        ) { backStackEntry ->
            val flow = PinFlow.valueOf(backStackEntry.arguments?.getString("flow") ?: PinFlow.CREATE.name)
            PinSetupScreen(flow = flow, onDone = { navController.popBackStack() })
        }
        composable(Screen.IssuedInvites.route) {
            IssuedInvitesRoute(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.Network.route) {
            NetworkScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.Diagnostics.route) {
            DiagnosticsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.RecoverySetup.route) {
            // The copy of a silently made key, or the gate's own flow when there is none.
            RecoverySetupScreen(onDone = { navController.popBackStack() })
        }
    }
}
