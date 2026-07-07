package com.construct.messenger.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.construct.messenger.ui.screens.chat.ChatScreen
import com.construct.messenger.ui.screens.main.MainTabView
import com.construct.messenger.ui.screens.onboarding.OnboardingScreen
import com.construct.messenger.ui.screens.settings.SettingsScreen
import com.construct.messenger.ui.screens.splash.SplashScreen

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
                onNavigateToMain = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onInitialized = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Screen.Main.route) {
            MainTabView(
                onNavigateToChat = { contactId ->
                    navController.navigate(Screen.Chat.createRoute(contactId)) {
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument("contactId") { type = NavType.StringType })
        ) { backStackEntry ->
            ChatScreen(
                contactId = requireNotNull(backStackEntry.arguments?.getString("contactId")) {
                    "Missing contactId route argument"
                },
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