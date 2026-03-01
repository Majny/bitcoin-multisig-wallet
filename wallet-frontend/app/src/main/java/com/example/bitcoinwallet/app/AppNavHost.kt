package com.example.bitcoinwallet.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.example.bitcoinwallet.core.session.SessionStore
import com.example.bitcoinwallet.feature.trezorconnect.navigation.TrezorRoutes
import com.example.bitcoinwallet.feature.trezorconnect.navigation.trezorConnectGraph
import com.example.bitcoinwallet.feature.wallet.navigation.WalletRoutes
import com.example.bitcoinwallet.feature.wallet.navigation.walletGraph

@Composable
fun AppNavHost(
    navController: NavHostController,
    connectTrigger: Int
) {
    // Determine start destination based on session state
    val hasActiveSession = SessionStore.session != null && SessionStore.hasWalletSelected()
    val startDestination = if (hasActiveSession) WalletRoutes.Graph else TrezorRoutes.Graph

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        trezorConnectGraph(navController)
        walletGraph(navController)
    }

    // Každý nový Trezor callback inkrementuje connectTrigger → efekt se vždy provede.
    LaunchedEffect(connectTrigger) {
        if (connectTrigger > 0) {
            navController.navigate(TrezorRoutes.Resolve) {
                popUpTo(TrezorRoutes.Graph) { inclusive = false }
                launchSingleTop = true
            }
        }
    }
}

