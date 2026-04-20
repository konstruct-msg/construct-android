package com.maxeliseyev.konstructmessenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.maxeliseyev.konstructmessenger.ui.navigation.KonstructNavHost
import com.maxeliseyev.konstructmessenger.ui.theme.CTColor
import com.maxeliseyev.konstructmessenger.ui.theme.KonstructMessengerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KonstructMessengerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = CTColor.bg
                ) {
                    val navController = rememberNavController()
                    KonstructNavHost(navController = navController)
                }
            }
        }
    }
}