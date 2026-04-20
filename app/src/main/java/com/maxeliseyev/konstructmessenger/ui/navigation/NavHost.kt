package com.maxeliseyev.konstructmessenger.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.maxeliseyev.konstructmessenger.ui.screens.main.MainScreen
import com.maxeliseyev.konstructmessenger.ui.screens.onboarding.OnboardingScreen
import com.maxeliseyev.konstructmessenger.ui.screens.splash.SplashScreen

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
                onNavigateToOnboarding = { navController.navigate(Screen.Onboarding.route) },
                onNavigateToMain = { navController.navigate(Screen.Main.route) }
            )
        }
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onInitialized = { navController.navigate(Screen.Main.route) }
            )
        }
        composable(Screen.Main.route) {
            MainScreen(
                onNavigateToChat = { contactId ->
                    navController.navigate(Screen.Chat.createRoute(contactId))
                }
            )
        }
    }
}