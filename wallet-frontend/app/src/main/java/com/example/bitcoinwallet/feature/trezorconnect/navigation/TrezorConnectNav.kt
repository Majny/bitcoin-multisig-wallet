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
                    launcher.openGetPublicKey(context, "m/84'/$coinType'/0'", network)
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
                    val identity = SessionStore.pendingIdentity
                        ?: run {
                            navController.navigate(TrezorRoutes.Connect) {
                                popUpTo(TrezorRoutes.Graph) { inclusive = false }
                                launchSingleTop = true
                            }
                            return@ResolveWalletScreen
                        }

                    // pass trezor identity
                    val session = signer.loginWithTrezor(identity)
                    SessionStore.session = session
                    SessionStore.walletsFetchedAtMs = System.currentTimeMillis()

                    if (SessionStore.hasWalletSelected()) {
                        // Navigate to wallet dashboard
                        navController.navigate(WalletRoutes.Graph) {
                            popUpTo(TrezorRoutes.Graph) { inclusive = true }
                            launchSingleTop = true
                        }
                    } else {
                        navController.navigate(TrezorRoutes.SelectAccount) {
                            launchSingleTop = true
                        }
                    }
                }
            )
        }

        composable(TrezorRoutes.SelectAccount) {
            // Zobraz jen wallety sítě, se kterou se právě přihlásilo.
            // pendingIdentity.derivationPath: "m/84'/1'/0'" = testnet, "m/84'/0'/0'" = mainnet
            val connectedNetwork = SessionStore.pendingIdentity?.derivationPath
                ?.let { if (it.contains("'/1'/")) "testnet" else "mainnet" }
                ?: "mainnet"
            val wallets = SessionStore.session?.user?.wallets
                .orEmpty()
                .filter { it.network == connectedNetwork }

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
