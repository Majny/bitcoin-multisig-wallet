package com.example.bitcoinwallet.feature.trezorconnect.navigation

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.example.bitcoinwallet.core.session.SessionStore
import com.example.bitcoinwallet.core.signer.MobileSigner
import com.example.bitcoinwallet.core.trezor.TrezorDeeplinkLauncher
import com.example.bitcoinwallet.feature.trezorconnect.ui.ResolveWalletScreen
import com.example.bitcoinwallet.feature.trezorconnect.ui.SelectAccountScreen
import com.example.bitcoinwallet.feature.trezorconnect.ui.TrezorConnectScreen

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
                onConnect = { launcher.openGetPublicKey(context) }
            )
        }

        composable(TrezorRoutes.Resolve) {
            val backendBaseUrl = "http://100.91.223.40:8080/api/v1"
            val signer = remember { MobileSigner(backendBaseUrl) }

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
                        navController.popBackStack(
                            route = TrezorRoutes.Graph,
                            inclusive = true
                        )
                    } else {
                        navController.navigate(TrezorRoutes.SelectAccount) {
                            launchSingleTop = true
                        }
                    }
                }
            )
        }

        composable(TrezorRoutes.SelectAccount) {
            val wallets = SessionStore.session?.user?.wallets.orEmpty()

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

                    navController.popBackStack(
                        route = TrezorRoutes.Graph,
                        inclusive = true
                    )
                }
            )
        }
    }
}
