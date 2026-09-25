package com.construct.messenger.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.construct.messenger.ui.screens.chat.ChatScreen
import com.construct.messenger.ui.screens.invite.ContactQrScreen
import com.construct.messenger.ui.screens.invite.QrScannerScreen
import com.construct.messenger.ui.screens.main.MainTabView
import com.construct.messenger.ui.screens.onboarding.OnboardingScreen
import com.construct.messenger.ui.screens.orientation.OrientationScreen
import com.construct.messenger.ui.screens.settings.SettingsScreen
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
                        navController.navigate(Screen.Main.createRoute(startTab = TAB_SYNAPS)) {
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
                onSignedOut = {
                    navController.navigate(Screen.Onboarding.route) {
                        popUpTo(Screen.Main.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onScanQr = { navController.navigate(Screen.ScanQr.route) { launchSingleTop = true } },
                onShowMyQr = { navController.navigate(Screen.InviteQr.route) { launchSingleTop = true } },
            )
        }
        composable(Screen.InviteQr.route) {
            ContactQrScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.ScanQr.route) {
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
        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument("contactId") { type = NavType.StringType })
        ) { backStackEntry ->
            ChatScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
