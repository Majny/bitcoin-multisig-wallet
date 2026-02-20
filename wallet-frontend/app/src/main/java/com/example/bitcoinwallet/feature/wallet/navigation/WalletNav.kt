package com.example.bitcoinwallet.feature.wallet.navigation

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.example.bitcoinwallet.feature.wallet.ui.WalletDashboardScreen
import com.example.bitcoinwallet.feature.wallet.ui.SendTransactionScreen
import com.example.bitcoinwallet.feature.wallet.ui.CoinControlScreen
import com.example.bitcoinwallet.feature.wallet.viewmodel.WalletDashboardViewModel
import com.example.bitcoinwallet.feature.wallet.viewmodel.SendTransactionViewModel
import com.example.bitcoinwallet.feature.wallet.viewmodel.CoinControlViewModel

object WalletRoutes {
    const val Graph = "wallet_graph"
    const val Dashboard = "wallet_dashboard"
    const val Send = "wallet_send"
    const val CoinControl = "wallet_coin_control"
    const val Receive = "wallet_receive"
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
                    viewModel.createTransaction { psbtId ->
                        // After PSBT created, navigate back (or to sign screen in future)
                        navController.popBackStack()
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
            // TODO: ReceiveBtcScreen
        }
        
        composable(WalletRoutes.TransactionDetail) { backStackEntry ->
            val txId = backStackEntry.arguments?.getString("txId") ?: ""
            // TODO: TransactionDetailScreen(txId = txId)
        }
    }
}
