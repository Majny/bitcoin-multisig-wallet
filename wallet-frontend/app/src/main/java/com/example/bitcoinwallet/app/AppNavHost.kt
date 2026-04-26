package com.example.bitcoinwallet.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.example.bitcoinwallet.core.api.ApiConfig
import com.example.bitcoinwallet.core.session.SessionStore
import com.example.bitcoinwallet.core.signer.MobileSigner
import com.example.bitcoinwallet.core.signer.UserSession
import com.example.bitcoinwallet.feature.trezorconnect.navigation.TrezorRoutes
import com.example.bitcoinwallet.feature.trezorconnect.navigation.trezorConnectGraph
import com.example.bitcoinwallet.feature.wallet.navigation.WalletRoutes
import com.example.bitcoinwallet.feature.wallet.navigation.walletGraph
import android.util.Log

/*
 * Top-level Compose nav host. Mounts the trezor-connect graph and the
 * wallet graph, and centralises the cross-graph signals that need to
 * re-route the user from anywhere:
 *   - connectTrigger: a fresh Trezor login callback was received
 *   - sessionExpired: the backend rejected the JWT and refresh failed
 *   - cold-start restore: rehydrate the wallet list / route to account
 *     selection if a session was loaded from disk but no wallet is active
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    connectTrigger: Int
) {
    // If we already have an active session AND a selected wallet, jump
    // straight into the wallet graph — otherwise start at the connect flow.
    val hasActiveSession = SessionStore.session != null && SessionStore.hasWalletSelected()
    val startDestination = if (hasActiveSession) WalletRoutes.Graph else TrezorRoutes.Graph

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        trezorConnectGraph(navController)
        walletGraph(navController)
    }

    // Each new Trezor auth callback increments connectTrigger → re-navigate to Resolve.
    LaunchedEffect(connectTrigger) {
        if (connectTrigger > 0) {
            navController.navigate(TrezorRoutes.Resolve) {
                popUpTo(TrezorRoutes.Graph) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    // Route the user back to the connect screen when the backend rejects the
    // session (401 + refresh failed). Without this, the user would stay on a
    // broken screen with "Session expired..." and have to kill the app to
    // recover. Matches the WRONG_DEVICE flow in WalletNav.
    val sessionExpired by SessionStore.sessionExpired.collectAsState()
    LaunchedEffect(sessionExpired) {
        if (sessionExpired) {
            SessionStore.consumeSessionExpired()
            navController.navigate(TrezorRoutes.Connect) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    // Session restored from disk only carries the JWT; rehydrate the wallet list
    // so screens that rely on SessionStore.session.user.wallets work. If the user
    // previously switched account and then killed the app, we still have a valid
    // session but no wallet selected — route them directly to SelectAccount rather
    // than making them re-authenticate through Trezor.
    LaunchedEffect(Unit) {
        val s = SessionStore.session ?: return@LaunchedEffect
        if (s.user.wallets.isEmpty()) {
            try {
                val signer = MobileSigner(ApiConfig.API_GATEWAY_BASE_URL)
                val wallets = signer.listWallets(s.accessToken)
                SessionStore.session = UserSession(
                    accessToken = s.accessToken,
                    user = s.user.copy(wallets = wallets)
                )
            } catch (e: Exception) {
                Log.w("AppNavHost", "Failed to refresh wallet list after restore", e)
            }
        }
        if (!SessionStore.hasWalletSelected() && SessionStore.session?.user?.wallets?.isNotEmpty() == true) {
            navController.navigate(TrezorRoutes.SelectAccount) {
                popUpTo(0) { inclusive = true }
            }
        }
    }
}

