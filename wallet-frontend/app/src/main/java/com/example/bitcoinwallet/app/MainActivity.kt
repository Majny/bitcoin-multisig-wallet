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

    private var startConnected by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO: is it better?
        enableEdgeToEdge()

        startConnected = intent.getBooleanExtra("trezor_connected", false)

        setContent {
            BitcoinWalletTheme {
                Surface(modifier = Modifier) {
                    val navController = rememberNavController()
                    AppNavHost(
                        navController = navController,
                        startConnected = startConnected
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startConnected = intent.getBooleanExtra("trezor_connected", false)
    }
}
