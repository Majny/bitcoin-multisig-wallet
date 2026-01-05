package com.example.bitcoinwallet.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.example.bitcoinwallet.feature.trezorconnect.navigation.TrezorRoutes
import com.example.bitcoinwallet.feature.trezorconnect.navigation.trezorConnectGraph

@Composable
fun AppNavHost(
    navController: NavHostController,
    startConnected: Boolean
) {
    NavHost(
        navController = navController,
        startDestination = TrezorRoutes.Graph
    ) {
        trezorConnectGraph(navController)
    }

    LaunchedEffect(startConnected) {
        if (startConnected) {
            navController.navigate(TrezorRoutes.Resolve) {
                popUpTo(TrezorRoutes.Graph) { inclusive = false }
                launchSingleTop = true
            }
        }
    }
}

