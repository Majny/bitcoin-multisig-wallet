package com.example.bitcoinwallet.feature.wallet.navigation

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.example.bitcoinwallet.core.session.SessionStore
import com.example.bitcoinwallet.core.trezor.TrezorDeeplinkLauncher
import com.example.bitcoinwallet.feature.wallet.ui.WalletDashboardScreen
import com.example.bitcoinwallet.feature.wallet.ui.SendTransactionScreen
import com.example.bitcoinwallet.feature.wallet.ui.CoinControlScreen
import com.example.bitcoinwallet.feature.wallet.ui.TransactionSentScreen
import com.example.bitcoinwallet.feature.wallet.ui.ReceiveBtcScreen
import com.example.bitcoinwallet.feature.wallet.ui.TransactionDetailScreen
import com.example.bitcoinwallet.feature.wallet.viewmodel.WalletDashboardViewModel
import com.example.bitcoinwallet.feature.wallet.viewmodel.SendTransactionViewModel
import com.example.bitcoinwallet.feature.wallet.viewmodel.CoinControlViewModel
import com.example.bitcoinwallet.feature.wallet.viewmodel.ReceiveBtcViewModel
import com.example.bitcoinwallet.feature.wallet.viewmodel.TransactionDetailViewModel

object WalletRoutes {
    const val Graph = "wallet_graph"
    const val Dashboard = "wallet_dashboard"
    const val Send = "wallet_send"
    const val CoinControl = "wallet_coin_control"
    const val TransactionSent = "wallet_tx_sent/{amountSats}/{feeSats}"
    const val Receive = "wallet_receive"

    fun transactionSent(amountSats: Long, feeSats: Long) = "wallet_tx_sent/$amountSats/$feeSats"
    const val TransactionDetail = "wallet_transaction_detail/{txId}"
    
    fun transactionDetail(txId: String) = "wallet_transaction_detail/$txId"
}

fun NavGraphBuilder.walletGraph(navController: NavController) {
    navigation(
        startDestination = WalletRoutes.Dashboard,
        route = WalletRoutes.Graph
    ) {
        composable(WalletRoutes.Dashboard) {
            val viewModel: WalletDashboardViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsState()
            
            WalletDashboardScreen(
                balance = uiState.balance,
                transactions = uiState.transactions,
                onMenuClick = {
                    // TODO: Open drawer or menu
                },
                onSendClick = {
                    navController.navigate(WalletRoutes.Send)
                },
                onReceiveClick = {
                    navController.navigate(WalletRoutes.Receive)
                },
                onTransactionClick = { transaction ->
                    navController.navigate(WalletRoutes.transactionDetail(transaction.txid))
                }
            )
        }
        
        composable(WalletRoutes.Send) {
            val viewModel: SendTransactionViewModel = viewModel()
            val state by viewModel.uiState.collectAsState()
            val context = LocalContext.current
            val trezorLauncher = TrezorDeeplinkLauncher()

            // Check if Trezor returned a signed PSBT (via SessionStore)
            LaunchedEffect(Unit) {
                val signedPsbt = SessionStore.pendingSignedPsbt
                if (signedPsbt != null && state.awaitingTrezor) {
                    SessionStore.pendingSignedPsbt = null
                    viewModel.onTrezorSigned(signedPsbt) {
                        // On broadcast success, navigate to TransactionSent screen
                        val s = viewModel.uiState.value
                        navController.navigate(
                            WalletRoutes.transactionSent(s.totalSats, s.feeSats)
                        ) {
                            // Pop Send screen from backstack
                            popUpTo(WalletRoutes.Dashboard) { inclusive = false }
                        }
                    }
                }
            }

            SendTransactionScreen(
                state = state,
                onClose = { navController.popBackStack() },
                onRecipientChanged = viewModel::onRecipientChanged,
                onAmountChanged = viewModel::onAmountChanged,
                onFeePriorityChanged = viewModel::onFeePriorityChanged,
                onAutoSelectChanged = viewModel::onAutoSelectChanged,
                onCustomFeeRateChanged = viewModel::onCustomFeeRateChanged,
                onEditSelection = {
                    navController.navigate(WalletRoutes.CoinControl)
                },
                onCreateTransaction = {
                    viewModel.createTransaction { psbtBase64 ->
                        // Open Trezor Suite to sign the PSBT
                        trezorLauncher.openSignTransaction(context, psbtBase64)
                    }
                }
            )
        }

        composable(WalletRoutes.TransactionSent) { backStackEntry ->
            val amountSats = backStackEntry.arguments?.getString("amountSats")?.toLongOrNull() ?: 0L
            val feeSats = backStackEntry.arguments?.getString("feeSats")?.toLongOrNull() ?: 0L

            TransactionSentScreen(
                amountSats = amountSats,
                feeSats = feeSats,
                onReturnToWallet = {
                    navController.navigate(WalletRoutes.Dashboard) {
                        popUpTo(WalletRoutes.Graph) { inclusive = false }
                    }
                }
            )
        }
        
        composable(WalletRoutes.CoinControl) {
            val coinControlVm: CoinControlViewModel = viewModel()
            val coinControlState by coinControlVm.uiState.collectAsState()

            // Get the Send VM from the parent back stack entry so state is shared
            val sendBackStackEntry = navController.getBackStackEntry(WalletRoutes.Send)
            val sendVm: SendTransactionViewModel = viewModel(sendBackStackEntry)

            // Pre-select UTXOs that were already selected
            coinControlVm.setPreSelected(sendVm.getSelectedUtxoKeys())

            CoinControlScreen(
                state = coinControlState,
                onClose = { navController.popBackStack() },
                onConfirm = {
                    // Pass selected UTXOs back to Send VM
                    sendVm.onUtxosSelected(coinControlVm.getSelectedUtxos())
                    navController.popBackStack()
                },
                onToggleUtxo = coinControlVm::toggleUtxo,
                onToggleSortMenu = coinControlVm::toggleSortMenu,
                onSortOrderChanged = coinControlVm::onSortOrderChanged
            )
        }

        composable(WalletRoutes.Receive) {
            val viewModel: ReceiveBtcViewModel = viewModel()
            val state by viewModel.uiState.collectAsState()
            val context = LocalContext.current
            val trezorLauncher = TrezorDeeplinkLauncher()

            ReceiveBtcScreen(
                state = state,
                onClose = { navController.popBackStack() },
                onCopied = { viewModel.onCopied() },
                onShowOnTrezor = {
                    trezorLauncher.openGetAddress(context)
                }
            )
        }
        
        composable(WalletRoutes.TransactionDetail) { backStackEntry ->
            val txId = backStackEntry.arguments?.getString("txId") ?: ""
            val viewModel: TransactionDetailViewModel = viewModel()
            val state by viewModel.uiState.collectAsState()

            LaunchedEffect(txId) {
                viewModel.loadTransaction(txId)
            }

            TransactionDetailScreen(
                state = state,
                onClose = { navController.popBackStack() }
            )
        }
    }
}
