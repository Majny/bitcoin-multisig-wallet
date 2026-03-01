package com.example.bitcoinwallet.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.example.bitcoinwallet.ui.theme.BitcoinWalletTheme

class MainActivity : ComponentActivity() {

    private var connectTrigger by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (intent.getBooleanExtra("trezor_connected", false)) connectTrigger++

        setContent {
            BitcoinWalletTheme {
                Surface(modifier = Modifier) {
                    val navController = rememberNavController()
                    AppNavHost(
                        navController = navController,
                        connectTrigger = connectTrigger
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("trezor_connected", false)) connectTrigger++
    }
}
