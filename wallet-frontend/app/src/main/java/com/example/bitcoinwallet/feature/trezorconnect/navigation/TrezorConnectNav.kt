package com.example.bitcoinwallet.feature.trezorconnect.navigation

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.example.bitcoinwallet.core.api.ApiConfig
import com.example.bitcoinwallet.core.session.SessionStore
import com.example.bitcoinwallet.core.signer.MobileSigner
import com.example.bitcoinwallet.core.trezor.TrezorDeeplinkLauncher
import com.example.bitcoinwallet.feature.trezorconnect.ui.ResolveWalletScreen
import com.example.bitcoinwallet.feature.trezorconnect.ui.SelectAccountScreen
import com.example.bitcoinwallet.feature.trezorconnect.ui.TrezorConnectScreen
import com.example.bitcoinwallet.feature.wallet.navigation.WalletRoutes

object TrezorRoutes {
    const val Graph = "trezor_graph"
    const val Connect = "trezor_connect"
    const val Resolve = "trezor_resolve"
    const val SelectAccount = "trezor_select_account"
}

/*
 * Three-step onboarding: Connect (pick network, launch Trezor deeplink) →
 * Resolve (exchange xpubs for a session, run discovery) → SelectAccount
 * (pick which discovered wallet to open). Everything outside here treats
 * "session with active wallet" as the only valid entry into the wallet
 * graph - this graph is what establishes both.
 */
fun NavGraphBuilder.trezorConnectGraph(navController: NavController) {
    navigation(
        startDestination = TrezorRoutes.Connect,
        route = TrezorRoutes.Graph
    ) {

        composable(TrezorRoutes.Connect) {
            val context = LocalContext.current
            val launcher = TrezorDeeplinkLauncher()

            TrezorConnectScreen(
                onConnect = { network ->
                    val coinType = if (network == "testnet") 1 else 0
                    // BIP-44 account discovery: request accounts 0–9 in a single bundle call
                    val paths = (0..9).map { "m/84'/$coinType'/$it'" }
                    SessionStore.pendingBatchXpubs = mutableListOf()
                    launcher.openGetPublicKeyBundle(context, paths, network)
                }
            )
        }

        composable(TrezorRoutes.Resolve) {
            val signer = remember { MobileSigner(ApiConfig.API_GATEWAY_BASE_URL) }

            ResolveWalletScreen(
                onErrorGoBack = {
                    SessionStore.clearAuth()
                    navController.navigate(TrezorRoutes.Connect) {
                        popUpTo(TrezorRoutes.Graph) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                runResolve = {
                    // Use batch xpubs if available, otherwise fall back to single identity.
                    // Copy to local list to avoid ConcurrentModificationException
                    // (clearAuth may reset the volatile MutableList on another thread).
                    val identities = ArrayList(SessionStore.pendingBatchXpubs).ifEmpty {
                        val single = SessionStore.pendingIdentity
                            ?: run {
                                navController.navigate(TrezorRoutes.Connect) {
                                    popUpTo(TrezorRoutes.Graph) { inclusive = false }
                                    launchSingleTop = true
                                }
                                return@ResolveWalletScreen
                            }
                        listOf(single)
                    }

                    if (identities.isEmpty()) {
                        throw Exception("No identities received from Trezor")
                    }

                    // Login with all collected xpubs - backend scans and creates wallets for active accounts.
                    // Store the refresh token BEFORE the session so persistSession() picks it up.
                    val loginResult = signer.loginWithTrezor(identities)
                    SessionStore.refreshToken = loginResult.refreshToken
                    SessionStore.session = loginResult.session
                    val session = loginResult.session

                    // Check if any wallets were found for the connected network
                    val connectedNetwork = identities.firstOrNull()?.derivationPath
                        ?.let { if (it.contains("'/1'/")) "testnet" else "mainnet" }
                        ?: "testnet"
                    SessionStore.selectedNetwork = connectedNetwork
                    val networkWallets = session.user.wallets.filter { it.network == connectedNetwork }

                    if (networkWallets.isEmpty()) {
                        throw Exception("No accounts with activity found on $connectedNetwork")
                    }

                    // Show wallet selection
                    navController.navigate(TrezorRoutes.SelectAccount) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(TrezorRoutes.SelectAccount) {
            // Show only wallets on the network the user just logged in on.
            // pendingIdentity.derivationPath: "m/84'/1'/0'" = testnet,
            // "m/84'/0'/0'" = mainnet. After a restart pendingIdentity is null,
            // so fall back to SessionStore.selectedNetwork which is persisted
            // alongside the session.
            val connectedNetwork = SessionStore.pendingIdentity?.derivationPath
                ?.let { if (it.contains("'/1'/")) "testnet" else "mainnet" }
                ?: SessionStore.selectedNetwork
            val wallets = SessionStore.session?.user?.wallets
                .orEmpty()
                .filter { it.network == connectedNetwork }
                .filter { it.type != com.example.bitcoinwallet.core.signer.WalletType.MULTI_SIG }

            SelectAccountScreen(
                wallets = wallets,
                onClose = {
                    navController.navigate(TrezorRoutes.Connect) {
                        popUpTo(TrezorRoutes.Graph) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onConfirm = { selected ->
                    SessionStore.activeWalletId = selected.id
                    SessionStore.activeAccountIndex = selected.accountIndex

                    // Navigate to wallet dashboard
                    navController.navigate(WalletRoutes.Graph) {
                        popUpTo(TrezorRoutes.Graph) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}
