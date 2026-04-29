package com.example.bitcoinwallet.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.example.bitcoinwallet.core.session.SessionPersistence
import com.example.bitcoinwallet.core.session.SessionStore
import com.example.bitcoinwallet.ui.theme.BitcoinWalletTheme
import com.example.bitcoinwallet.ui.theme.DarkBackground

/*
 * Single Activity for the app. Hosts the Compose nav-host, wires up the
 * edge-to-edge theme, and rehydrates the session from disk on cold start
 * so a returning user lands on the dashboard rather than the Trezor
 * connect screen.
 */
class MainActivity : ComponentActivity() {

    // Bumped every time the launcher Intent carries the trezor_connected
    // extra. AppNavHost keys off it to re-run the post-connect flow.
    private var connectTrigger by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Rehydrate the session from disk so a previously signed-in user is
        // not booted back to the Trezor login screen after a process kill.
        SessionPersistence.init(applicationContext)
        if (SessionStore.session == null) {
            SessionStore.restoreFromPersistence()
        }

        if (intent.getBooleanExtra("trezor_connected", false)) connectTrigger++

        setContent {
            BitcoinWalletTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                        val navController = rememberNavController()
                        AppNavHost(
                            navController = navController,
                            connectTrigger = connectTrigger
                        )
                    }
                }
            }
        }
    }

    // Deeplinks from Trezor Suite Mobile come in via new intents rather than
    // a fresh activity launch when singleTask is set - re-check the extras.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("trezor_connected", false)) connectTrigger++
    }
}
