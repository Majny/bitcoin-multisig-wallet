package com.example.bitcoinwallet.feature.wallet.navigation

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
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
import com.example.bitcoinwallet.feature.wallet.ui.SettingsScreen
import com.example.bitcoinwallet.feature.wallet.ui.MultisigWalletsScreen
import com.example.bitcoinwallet.feature.wallet.ui.ImportWalletScreen
import com.example.bitcoinwallet.feature.wallet.ui.MultisigDetailScreen
import com.example.bitcoinwallet.feature.wallet.ui.PsbtListScreen
import com.example.bitcoinwallet.feature.wallet.ui.PsbtDetailScreen
import com.example.bitcoinwallet.feature.wallet.viewmodel.ImportWalletViewModel
import com.example.bitcoinwallet.feature.wallet.viewmodel.MultisigDetailViewModel
import com.example.bitcoinwallet.feature.wallet.viewmodel.PsbtListViewModel
import com.example.bitcoinwallet.feature.wallet.viewmodel.PsbtDetailViewModel
import com.example.bitcoinwallet.feature.wallet.viewmodel.WalletDashboardViewModel
import com.example.bitcoinwallet.feature.wallet.viewmodel.MultisigWalletsViewModel
import com.example.bitcoinwallet.feature.wallet.viewmodel.SendTransactionViewModel
import com.example.bitcoinwallet.feature.wallet.viewmodel.CoinControlViewModel
import com.example.bitcoinwallet.feature.wallet.viewmodel.ReceiveBtcViewModel
import com.example.bitcoinwallet.feature.wallet.viewmodel.TransactionDetailViewModel
import com.example.bitcoinwallet.ui.components.DrawerContent
import com.example.bitcoinwallet.ui.components.DrawerItem
import kotlinx.coroutines.launch

object WalletRoutes {
    const val Graph = "wallet_graph"
    const val Dashboard = "wallet_dashboard"
    const val Send = "wallet_send"
    const val CoinControl = "wallet_coin_control"
    const val TransactionSent = "wallet_tx_sent/{amountSats}/{feeSats}"
    const val Receive = "wallet_receive"
    const val MultisigWallets = "wallet_multisig_list"
    const val ImportWallet = "wallet_import"
    const val Settings = "wallet_settings"

    fun transactionSent(amountSats: Long, feeSats: Long) = "wallet_tx_sent/$amountSats/$feeSats"
    const val TransactionDetail = "wallet_transaction_detail/{txId}"
    
    fun transactionDetail(txId: String) = "wallet_transaction_detail/$txId"

    const val MultisigDetail = "wallet_multisig_detail/{walletId}/{walletName}/{m}/{n}"

    fun multisigDetail(walletId: String, walletName: String, m: Int, n: Int): String {
        val encoded = java.net.URLEncoder.encode(walletName, "UTF-8")
        return "wallet_multisig_detail/$walletId/$encoded/$m/$n"
    }

    const val PsbtList = "wallet_psbt_list/{walletId}"

    fun psbtList(walletId: String) = "wallet_psbt_list/$walletId"

    const val CreatePsbt = "wallet_create_psbt/{walletId}"

    fun createPsbt(walletId: String) = "wallet_create_psbt/$walletId"

    const val PsbtDetail = "wallet_psbt_detail/{psbtId}"

    fun psbtDetail(psbtId: String) = "wallet_psbt_detail/$psbtId"
}

fun NavGraphBuilder.walletGraph(navController: NavController) {
    navigation(
        startDestination = WalletRoutes.Dashboard,
        route = WalletRoutes.Graph
    ) {
        composable(WalletRoutes.Dashboard) {
            val viewModel: WalletDashboardViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsState()
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val scope = rememberCoroutineScope()

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    DrawerContent(
                        onItemClick = { item ->
                            scope.launch { drawerState.close() }
                            when (item) {
                                DrawerItem.HOME -> { /* already on dashboard */ }
                                DrawerItem.MULTISIG -> navController.navigate(WalletRoutes.MultisigWallets)
                                DrawerItem.SETTINGS -> navController.navigate(WalletRoutes.Settings)
                            }
                        }
                    )
                }
            ) {
                WalletDashboardScreen(
                    balance = uiState.balance,
                    transactions = uiState.transactions,
                    isLoading = uiState.isLoading,
                    error = uiState.error,
                    onMenuClick = {
                        scope.launch { drawerState.open() }
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
        }
        
        composable(WalletRoutes.Send) {
            val viewModel: SendTransactionViewModel = viewModel()
            val state by viewModel.uiState.collectAsState()
            val context = LocalContext.current
            val trezorLauncher = TrezorDeeplinkLauncher()

            // Sleduj StateFlow — re-spustí se pokaždé, když Trezor vrátí podepsaný PSBT,
            // včetně případu kdy activity přežila přes onNewIntent (FLAG_SINGLE_TOP).
            val pendingSignedPsbt by SessionStore.pendingSignedPsbt.collectAsState()
            LaunchedEffect(pendingSignedPsbt) {
                val signedPsbt = pendingSignedPsbt
                if (signedPsbt != null && state.awaitingTrezor) {
                    SessionStore.setPendingSignedPsbt(null)
                    viewModel.onTrezorSigned(signedPsbt) {
                        val s = viewModel.uiState.value
                        navController.navigate(
                            WalletRoutes.transactionSent(s.totalSats, s.feeSats)
                        ) {
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

            // Pre-select UTXOs jednou při prvním zobrazení — nesmí být v těle composable,
            // protože by se volalo při každé rekomposici a resetovalo uživatelův výběr.
            LaunchedEffect(Unit) {
                coinControlVm.setPreSelected(sendVm.getSelectedUtxoKeys())
            }

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

        composable(WalletRoutes.MultisigWallets) {
            val viewModel: MultisigWalletsViewModel = viewModel()
            val state by viewModel.uiState.collectAsState()
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val scope = rememberCoroutineScope()

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    DrawerContent(
                        onItemClick = { item ->
                            scope.launch { drawerState.close() }
                            when (item) {
                                DrawerItem.HOME -> navController.navigate(WalletRoutes.Dashboard) {
                                    popUpTo(WalletRoutes.Graph) { inclusive = false }
                                }
                                DrawerItem.MULTISIG -> { /* already here */ }
                                DrawerItem.SETTINGS -> navController.navigate(WalletRoutes.Settings)
                            }
                        }
                    )
                }
            ) {
                MultisigWalletsScreen(
                    state = state,
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onImportWallet = {
                        navController.navigate(WalletRoutes.ImportWallet)
                    },
                    onWalletClick = { wallet ->
                        navController.navigate(
                            WalletRoutes.multisigDetail(
                                walletId = wallet.walletId,
                                walletName = wallet.label,
                                m = wallet.m,
                                n = wallet.n
                            )
                        )
                    }
                )
            }
        }

        composable(WalletRoutes.MultisigDetail) { backStackEntry ->
            val walletId = backStackEntry.arguments?.getString("walletId") ?: ""
            val walletName = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("walletName") ?: "", "UTF-8"
            )
            val m = backStackEntry.arguments?.getString("m")?.toIntOrNull() ?: 0
            val n = backStackEntry.arguments?.getString("n")?.toIntOrNull() ?: 0

            val viewModel: MultisigDetailViewModel = viewModel()
            val state by viewModel.uiState.collectAsState()

            LaunchedEffect(walletId) {
                viewModel.loadWallet(walletId, walletName, m, n)
            }

            MultisigDetailScreen(
                state = state,
                onClose = { navController.popBackStack() },
                onPsbtsClick = {
                    navController.navigate(WalletRoutes.psbtList(walletId))
                },
                onReceiveClick = {
                    navController.navigate(WalletRoutes.Receive)
                },
                onTransactionClick = { transaction ->
                    navController.navigate(WalletRoutes.transactionDetail(transaction.txid))
                }
            )
        }

        composable(WalletRoutes.ImportWallet) {
            val viewModel: ImportWalletViewModel = viewModel()
            val state by viewModel.uiState.collectAsState()

            ImportWalletScreen(
                state = state,
                onClose = { navController.popBackStack() },
                onDescriptorChanged = viewModel::onDescriptorChanged,
                onWalletNameChanged = viewModel::onWalletNameChanged,
                onFileContent = viewModel::onDescriptorScanned,
                onScanQr = { /* TODO: launch QR scanner */ },
                onImport = {
                    viewModel.importWallet {
                        // On success, go back to multisig list
                        navController.popBackStack()
                    }
                }
            )
        }

        composable(WalletRoutes.PsbtList) { backStackEntry ->
            val walletId = backStackEntry.arguments?.getString("walletId") ?: ""

            val viewModel: PsbtListViewModel = viewModel()
            val state by viewModel.uiState.collectAsState()

            LaunchedEffect(walletId) {
                viewModel.loadPsbts(walletId)
            }

            PsbtListScreen(
                state = state,
                onClose = { navController.popBackStack() },
                onCreatePsbt = {
                    navController.navigate(WalletRoutes.createPsbt(walletId))
                },
                onImportPsbt = {
                    // TODO: import PSBT from file/QR
                },
                onPsbtClick = { psbt ->
                    navController.navigate(WalletRoutes.psbtDetail(psbt.id))
                }
            )
        }

        composable(WalletRoutes.CreatePsbt) { backStackEntry ->
            val walletId = backStackEntry.arguments?.getString("walletId") ?: ""

            // Ulož předchozí walletId pro obnovení při odchodu
            val previousWalletId = remember { SessionStore.activeWalletId }

            // Nastav activeWalletId SYNCHRONNĚ pomocí remember() — volá se před viewModel(),
            // takže ViewModel.init() načte správnou peněženku. LaunchedEffect by byl příliš pozdě.
            remember(walletId) { SessionStore.activeWalletId = walletId }

            val viewModel: SendTransactionViewModel = viewModel()
            val sendState by viewModel.uiState.collectAsState()

            // Obnov walletId při opuštění composable (navigace zpět nebo jinam)
            DisposableEffect(walletId) {
                onDispose { SessionStore.activeWalletId = previousWalletId }
            }

            SendTransactionScreen(
                state = sendState,
                title = "New PSBT",
                buttonText = "Create PSBT",
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
                    viewModel.createTransaction { _ ->
                        // PSBT je uložen v DB — všichni cosigneři ho uvidí
                        navController.popBackStack()
                    }
                }
            )
        }

        composable(WalletRoutes.PsbtDetail) { backStackEntry ->
            val psbtId = backStackEntry.arguments?.getString("psbtId") ?: ""

            val viewModel: PsbtDetailViewModel = viewModel()
            val detailState by viewModel.uiState.collectAsState()
            val context = LocalContext.current
            val trezorLauncher = TrezorDeeplinkLauncher()

            LaunchedEffect(psbtId) {
                viewModel.loadPsbt(psbtId)
            }

            // Sleduj StateFlow — funguje i při návratu přes onNewIntent (FLAG_SINGLE_TOP)
            val pendingSignedPsbt by SessionStore.pendingSignedPsbt.collectAsState()
            LaunchedEffect(pendingSignedPsbt) {
                if (pendingSignedPsbt != null) {
                    SessionStore.setPendingSignedPsbt(null)
                    viewModel.refresh()
                }
            }

            PsbtDetailScreen(
                state = detailState,
                onClose = { navController.popBackStack() },
                onSignPsbt = {
                    trezorLauncher.openSignTransaction(context, detailState.psbtBase64)
                },
                onExportPsbt = {
                    // TODO: share/export PSBT base64
                },
                onBroadcast = {
                    viewModel.broadcast()
                },
                onShowRecipients = {
                    viewModel.showSignersDialog()
                },
                onDismissRecipients = {
                    viewModel.dismissSignersDialog()
                }
            )
        }

        composable(WalletRoutes.Settings) {
            SettingsScreen(
                onClose = { navController.popBackStack() },
                onDisconnect = {
                    SessionStore.clearAuth()
                    navController.navigate(
                        com.example.bitcoinwallet.feature.trezorconnect.navigation.TrezorRoutes.Graph
                    ) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
