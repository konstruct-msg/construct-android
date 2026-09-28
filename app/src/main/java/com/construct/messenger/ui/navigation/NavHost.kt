package com.construct.messenger.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.construct.messenger.ui.screens.chat.ChatScreen
import com.construct.messenger.ui.screens.chat.SafetyNumberScreen
import com.construct.messenger.ui.screens.invite.ContactQrScreen
import com.construct.messenger.ui.screens.invite.QrScannerScreen
import com.construct.messenger.ui.screens.recovery.RecoveryGated
import com.construct.messenger.ui.screens.recovery.RecoveryPromptScreen
import com.construct.messenger.ui.screens.main.MainTabView
import com.construct.messenger.ui.screens.onboarding.OnboardingScreen
import com.construct.messenger.ui.screens.orientation.OrientationScreen
import com.construct.messenger.ui.screens.settings.AccountScreen
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
                }
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
                        // Registration stays one step; the recovery phrase is asked for here,
                        // before anyone reaches for an invite (which waits on it).
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
            MainTabView(
                startTab = backStackEntry.arguments?.getInt("startTab") ?: 0,
                onNavigateToChat = { contactId ->
                    navController.navigate(Screen.Chat.createRoute(contactId)) {
                        launchSingleTop = true
                    }
                },
                onScanQr = { navController.navigate(Screen.ScanQr.route) { launchSingleTop = true } },
                settingsNavigation = SettingsNavigation(
                    onAccount = { navController.navigate(Screen.Account.route) { launchSingleTop = true } },
                    onInvite = { navController.navigate(Screen.InviteQr.route) { launchSingleTop = true } },
                    onSecurity = { navController.navigate(Screen.Security.route) { launchSingleTop = true } },
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
        // Both invite surfaces wait on the recovery phrase: an invite names the account's
        // address, and this device learns it only from the phrase.
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
                onSignedOut = {
                    navController.navigate(Screen.Onboarding.route) {
                        popUpTo(Screen.Main.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Screen.Security.route) {
            SecurityScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}
