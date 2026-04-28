package com.example.bitcoinwallet.feature.wallet.navigation

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navigation
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.bitcoinwallet.core.session.SessionStore
import com.example.bitcoinwallet.core.trezor.TrezorDeeplinkLauncher
import com.example.bitcoinwallet.feature.wallet.ui.WalletDashboardScreen
import com.example.bitcoinwallet.feature.wallet.ui.QrScannerScreen
import com.example.bitcoinwallet.feature.wallet.ui.UrQrScannerScreen
import com.example.bitcoinwallet.feature.wallet.ui.SendTransactionScreen
import com.example.bitcoinwallet.feature.wallet.ui.CoinControlScreen
import com.example.bitcoinwallet.feature.wallet.ui.TransactionErrorScreen
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

/*
 * Wallet-feature nav graph. Hosts every post-login screen — dashboard,
 * send/coin-control, receive, PSBT list/detail, multisig management,
 * settings — plus the Trezor sign callback plumbing that routes the
 * pendingSignedPsbt StateFlow from SessionStore back into the correct
 * flow (SEND vs PSBT_DETAIL, guarded by SessionStore.activeSignFlow).
 */
object WalletRoutes {
    const val Graph = "wallet_graph"
    const val Dashboard = "wallet_dashboard"
    const val Send = "wallet_send"
    const val CoinControl = "wallet_coin_control"
    const val TransactionSent = "wallet_tx_sent/{amountSats}/{feeSats}/{walletId}"
    const val Receive = "wallet_receive"
    const val MultisigWallets = "wallet_multisig_list"
    const val ImportWallet = "wallet_import"
    const val Settings = "wallet_settings"

    fun transactionSent(amountSats: Long, feeSats: Long, walletId: String?): String {
        val encoded = java.net.URLEncoder.encode(walletId.orEmpty(), "UTF-8")
        return "wallet_tx_sent/$amountSats/$feeSats/$encoded"
    }
    const val TransactionDetail =
        "wallet_transaction_detail/{txId}?depth={depth}&fromVout={fromVout}"
    const val MaxTransactionDepth = 5

    fun transactionDetail(txId: String, depth: Int = 1, fromVout: Int = -1) =
        "wallet_transaction_detail/$txId?depth=$depth&fromVout=$fromVout"

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

    const val QrScanner = "wallet_qr_scanner"
    const val UrQrScanner = "wallet_ur_qr_scanner"

    const val TransactionError = "wallet_tx_error/{message}"
    fun transactionError(message: String): String {
        val encoded = java.net.URLEncoder.encode(message, "UTF-8")
        return "wallet_tx_error/$encoded"
    }
}

fun NavGraphBuilder.walletGraph(navController: NavController) {
    navigation(
        startDestination = WalletRoutes.Dashboard,
        route = WalletRoutes.Graph
    ) {
        composable(WalletRoutes.Dashboard) { backStackEntry ->
            val viewModel: WalletDashboardViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsState()
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            val walletNetwork = remember {
                SessionStore.session?.user?.wallets
                    ?.find { it.id == SessionStore.activeWalletId }?.network ?: "mainnet"
            }

            // Refresh data when returning to this screen (e.g. after sending a transaction)
            DisposableEffect(backStackEntry) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        viewModel.refresh()
                    }
                }
                backStackEntry.lifecycle.addObserver(observer)
                onDispose {
                    backStackEntry.lifecycle.removeObserver(observer)
                }
            }

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
                    network = walletNetwork,
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
        
        composable(WalletRoutes.Send) { backStackEntry ->
            val viewModel: SendTransactionViewModel = viewModel()
            val state by viewModel.uiState.collectAsState()
            val context = LocalContext.current
            val trezorLauncher = TrezorDeeplinkLauncher()
            val walletNetwork = remember {
                SessionStore.session?.user?.wallets
                    ?.find { it.id == SessionStore.activeWalletId }?.network ?: "testnet"
            }

            // QR scan result — QrScannerScreen drops it into savedStateHandle.
            val qrAddress by backStackEntry.savedStateHandle
                .getStateFlow<String?>("qr_address", null)
                .collectAsState()
            LaunchedEffect(qrAddress) {
                val addr = qrAddress
                if (addr != null) {
                    viewModel.onRecipientChanged(addr)
                    backStackEntry.savedStateHandle.remove<String>("qr_address")
                }
            }

            // Observe pendingSignedPsbt as a StateFlow — the handler re-runs
            // every time Trezor returns, including after onNewIntent
            // (FLAG_SINGLE_TOP). Deliberately no awaitingTrezor gate: a
            // previous implementation had a 3 s timeout that reset the flag
            // before the user could confirm on the device (10–30 s in
            // practice), which dropped the signed result on the floor.
            val pendingSignedPsbt by SessionStore.pendingSignedPsbt.collectAsState()
            val pendingSignType by SessionStore.pendingSignType.collectAsState()
            LaunchedEffect(pendingSignedPsbt) {
                // Ignore callbacks belonging to another flow (PSBT_DETAIL) so the
                // Send composable does not swallow a result meant for the PSBT detail screen.
                if (SessionStore.activeSignFlow.value != com.example.bitcoinwallet.core.session.SignFlow.SEND) {
                    return@LaunchedEffect
                }
                val signedData = pendingSignedPsbt
                val signType = pendingSignType
                when {
                    signedData == "CANCELLED" -> {
                        SessionStore.setPendingSignedPsbt(null)
                        SessionStore.setPendingSignType(null)
                        SessionStore.setActiveSignFlow(null)
                        viewModel.resetTrezorState()
                    }
                    signedData == "WRONG_DEVICE" -> {
                        SessionStore.setPendingSignedPsbt(null)
                        SessionStore.setPendingSignType(null)
                        SessionStore.setActiveSignFlow(null)
                        SessionStore.setPendingLogoutReason(
                            "Signed out for security: connected Trezor did not match the one you signed in with."
                        )
                        SessionStore.clearAuth()
                        navController.navigate(
                            com.example.bitcoinwallet.feature.trezorconnect.navigation.TrezorRoutes.Connect
                        ) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                    signedData == "ERROR" -> {
                        SessionStore.setPendingSignedPsbt(null)
                        SessionStore.setPendingSignType(null)
                        SessionStore.setActiveSignFlow(null)
                        viewModel.onTrezorFailed("Trezor returned an invalid response. Please try again.")
                    }
                    signedData != null && signType != null -> {
                        SessionStore.setPendingSignedPsbt(null)
                        SessionStore.setPendingSignType(null)
                        SessionStore.setActiveSignFlow(null)
                        viewModel.onTrezorResult(signedData, signType) {}
                    }
                }
            }

            // No auto-timeout. Multisig review plus passphrase entry on the
            // device routinely takes more than two minutes, and any reset
            // that runs while the user is still confirming on Trezor
            // silently drops the eventual callback. The wait holds until
            // either the callback arrives or the user explicitly cancels
            // via the UI; an aborted wait still calls
            // SessionStore.clearPendingSignRequest so a callback arriving
            // after cancel is rejected at TrezorCallbackActivity by id
            // mismatch.

            // Navigate to success screen when broadcast completes.
            // Separate LaunchedEffect avoids lifecycle issues from navigating inside
            // a viewModelScope callback that may fire before the composable is RESUMED.
            // Reset the flag immediately so a re-entry (rotation, process restore) does
            // not re-trigger navigation.
            LaunchedEffect(state.broadcastSuccess) {
                if (state.broadcastSuccess) {
                    viewModel.consumeBroadcastSuccess()
                    navController.navigate(
                        WalletRoutes.transactionSent(
                            state.totalSats,
                            state.feeSats,
                            SessionStore.activeWalletId
                        )
                    ) {
                        popUpTo(WalletRoutes.Dashboard) { inclusive = false }
                    }
                }
            }

            SendTransactionScreen(
                state = state,
                onClose = { navController.popBackStack() },
                onRecipientChanged = viewModel::onRecipientChanged,
                onAmountChanged = viewModel::onAmountChanged,
                onAmountUnitToggled = viewModel::onAmountUnitToggled,
                onFeePriorityChanged = viewModel::onFeePriorityChanged,
                onAutoSelectChanged = viewModel::onAutoSelectChanged,
                onCustomFeeRateChanged = viewModel::onCustomFeeRateChanged,
                onScanQr = { navController.navigate(WalletRoutes.QrScanner) },
                onEditSelection = {
                    navController.navigate(WalletRoutes.CoinControl)
                },
                onCreateTransaction = {
                    viewModel.createTransaction(
                        onError = { errorMsg ->
                            navController.navigate(
                                WalletRoutes.transactionError(errorMsg)
                            ) {
                                popUpTo(WalletRoutes.Dashboard) { inclusive = false }
                            }
                        }
                    ) { psbtBase64, trezorParams ->
                        // Claim the sign callback for the Send flow before launching Trezor.
                        SessionStore.setActiveSignFlow(com.example.bitcoinwallet.core.session.SignFlow.SEND)
                        val launched = if (trezorParams != null) {
                            // Singlesig: use structured Trezor Connect params
                            Log.d("WalletNav", "Opening Trezor with STRUCTURED params: coin=${trezorParams.coin}, inputs=${trezorParams.inputs.size}, outputs=${trezorParams.outputs.size}, version=${trezorParams.version}, locktime=${trezorParams.locktime}")
                            trezorLauncher.openSignTransactionStructured(context, trezorParams)
                        } else {
                            // Fallback: raw PSBT (multisig or no params)
                            Log.d("WalletNav", "Opening Trezor with raw PSBT fallback, network=$walletNetwork, psbt length=${psbtBase64.length}")
                            trezorLauncher.openSignTransaction(context, psbtBase64, walletNetwork)
                        }
                        if (!launched) {
                            Log.e("WalletNav", "Failed to launch Trezor Suite — app may not be installed")
                            SessionStore.setActiveSignFlow(null)
                            viewModel.resetTrezorState()
                        }
                    }
                },
                onCancelTrezorWait = { viewModel.cancelTrezorWait() }
            )
        }

        composable(WalletRoutes.TransactionSent) { backStackEntry ->
            val amountSats = backStackEntry.arguments?.getString("amountSats")?.toLongOrNull() ?: 0L
            val feeSats = backStackEntry.arguments?.getString("feeSats")?.toLongOrNull() ?: 0L
            val walletId = backStackEntry.arguments?.getString("walletId")
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?.takeIf { it.isNotBlank() }

            TransactionSentScreen(
                amountSats = amountSats,
                feeSats = feeSats,
                onReturnToWallet = {
                    val sourceWallet = walletId?.let { id ->
                        SessionStore.session?.user?.wallets?.find { it.id == id }
                    }
                    if (sourceWallet != null &&
                        sourceWallet.type == com.example.bitcoinwallet.core.signer.WalletType.MULTI_SIG
                    ) {
                        SessionStore.activeWalletId = sourceWallet.id
                        navController.navigate(
                            WalletRoutes.multisigDetail(
                                walletId = sourceWallet.id,
                                walletName = sourceWallet.label ?: sourceWallet.id,
                                m = sourceWallet.m ?: 0,
                                n = sourceWallet.n ?: 0
                            )
                        ) {
                            popUpTo(WalletRoutes.Graph) { inclusive = false }
                        }
                    } else {
                        if (walletId != null) SessionStore.activeWalletId = walletId
                        navController.navigate(WalletRoutes.Dashboard) {
                            popUpTo(WalletRoutes.Graph) { inclusive = false }
                        }
                    }
                }
            )
        }
        
        composable(WalletRoutes.TransactionError) { backStackEntry ->
            val raw = backStackEntry.arguments?.getString("message")
            // URLDecoder.decode throws IllegalArgumentException on malformed
            // percent-encoding. An uncaught throw here would crash the
            // composable mid-render and the user ends up on a generic
            // "unknown error" fallback — show the raw arg instead so at
            // least the diagnostic text reaches the screen.
            val message = try {
                raw?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            } catch (e: IllegalArgumentException) {
                Log.w("WalletNav", "Malformed error message arg, showing raw", e)
                raw
            } ?: "An unknown error occurred"

            TransactionErrorScreen(
                message = message,
                onReturn = {
                    navController.navigate(WalletRoutes.Dashboard) {
                        popUpTo(WalletRoutes.Graph) { inclusive = false }
                    }
                }
            )
        }

        composable(WalletRoutes.CoinControl) {
            val coinControlVm: CoinControlViewModel = viewModel()
            val coinControlState by coinControlVm.uiState.collectAsState()

            // Get the Send VM from the parent back stack entry so state is shared.
            // CoinControl can be reached from both Send and CreatePsbt — try Send first,
            // then fall back to the previous entry (CreatePsbt) to avoid a crash.
            val sendBackStackEntry = remember {
                try {
                    navController.getBackStackEntry(WalletRoutes.Send)
                } catch (_: IllegalArgumentException) {
                    try {
                        navController.getBackStackEntry(WalletRoutes.CreatePsbt)
                    } catch (_: IllegalArgumentException) {
                        navController.previousBackStackEntry
                    }
                }
            }
            if (sendBackStackEntry == null) {
                navController.popBackStack()
                return@composable
            }
            val sendVm: SendTransactionViewModel = viewModel(sendBackStackEntry)

            // Pre-select UTXOs once on first entry. Must be inside LaunchedEffect —
            // doing it in the composable body would re-run on every recomposition
            // and overwrite the user's manual selection.
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
                onSortOrderChanged = coinControlVm::onSortOrderChanged,
                onUtxoDetail = { txid ->
                    navController.navigate(WalletRoutes.transactionDetail(txid))
                }
            )
        }

        composable(WalletRoutes.Receive) { backStackEntry ->
            val viewModel: ReceiveBtcViewModel = viewModel()
            val state by viewModel.uiState.collectAsState()
            val context = LocalContext.current
            val trezorLauncher = TrezorDeeplinkLauncher()

            // Clear the "verifying on Trezor" state when the user returns to the
            // screen. Trezor Suite does not route its showAddress callback back
            // into the app, so ON_RESUME is our only signal that verification
            // concluded (either confirmed or cancelled).
            DisposableEffect(backStackEntry) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        viewModel.onShowOnTrezorFinished()
                    }
                }
                backStackEntry.lifecycle.addObserver(observer)
                onDispose { backStackEntry.lifecycle.removeObserver(observer) }
            }

            ReceiveBtcScreen(
                state = state,
                onClose = { navController.popBackStack() },
                onCopied = { viewModel.onCopied() },
                onShowOnTrezor = {
                    val params = state.verifyAddressParams
                    if (params != null) {
                        viewModel.onShowOnTrezorStarted()
                        trezorLauncher.openGetAddress(
                            context = context,
                            path = params.path,
                            coin = params.coin,
                            scriptType = params.scriptType,
                            multisig = params.multisig
                        )
                    } else {
                        Log.w("WalletNav", "verifyAddressParams is null — cannot show on Trezor")
                    }
                }
            )
        }
        
        composable(
            route = WalletRoutes.TransactionDetail,
            arguments = listOf(
                navArgument("txId") { type = NavType.StringType },
                navArgument("depth") {
                    type = NavType.IntType
                    defaultValue = 1
                },
                navArgument("fromVout") {
                    type = NavType.IntType
                    defaultValue = -1
                }
            )
        ) { backStackEntry ->
            val txId = backStackEntry.arguments?.getString("txId") ?: ""
            val depth = backStackEntry.arguments?.getInt("depth") ?: 1
            val fromVout = backStackEntry.arguments?.getInt("fromVout") ?: -1
            val viewModel: TransactionDetailViewModel = viewModel()
            val state by viewModel.uiState.collectAsState()

            LaunchedEffect(txId) {
                viewModel.loadTransaction(txId)
            }

            TransactionDetailScreen(
                state = state,
                depth = depth,
                maxDepth = WalletRoutes.MaxTransactionDepth,
                highlightOutputIndex = fromVout.takeIf { it >= 0 },
                onBack = { navController.popBackStack() },
                onClose = {
                    val popped = navController.popBackStack(
                        route = WalletRoutes.Dashboard,
                        inclusive = false
                    )
                    if (!popped) {
                        navController.popBackStack(
                            route = WalletRoutes.MultisigDetail,
                            inclusive = false
                        )
                    }
                },
                onOpenPrevTx = { prevTxid, prevVout ->
                    if (depth < WalletRoutes.MaxTransactionDepth) {
                        navController.navigate(
                            WalletRoutes.transactionDetail(prevTxid, depth + 1, prevVout)
                        )
                    }
                }
            )
        }

        composable(WalletRoutes.MultisigWallets) { backStackEntry ->
            val viewModel: MultisigWalletsViewModel = viewModel()
            val state by viewModel.uiState.collectAsState()
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val scope = rememberCoroutineScope()

            val walletImported by backStackEntry.savedStateHandle
                .getStateFlow("wallet_imported", false)
                .collectAsState()
            LaunchedEffect(walletImported) {
                if (walletImported) {
                    viewModel.loadWallets()
                    backStackEntry.savedStateHandle.remove<Boolean>("wallet_imported")
                }
            }

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
                        // activeAccountIndex was set at login to the current singlesig
                        // wallet's BIP-48 account — keep it so "me" detection on multisig
                        // signers lines up with the logged-in key.
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

            // Switch activeWalletId to this multisig wallet so Receive uses the right address
            val previousWalletId = remember { SessionStore.activeWalletId }
            remember(walletId) { SessionStore.activeWalletId = walletId }
            DisposableEffect(walletId) {
                onDispose { SessionStore.activeWalletId = previousWalletId }
            }

            val viewModel: MultisigDetailViewModel = viewModel()
            val state by viewModel.uiState.collectAsState()
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val scope = rememberCoroutineScope()

            LaunchedEffect(walletId) {
                viewModel.loadWallet(walletId, walletName, m, n)
            }

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    DrawerContent(
                        onItemClick = { item ->
                            scope.launch { drawerState.close() }
                            when (item) {
                                DrawerItem.HOME -> navController.popBackStack(WalletRoutes.Dashboard, inclusive = false)
                                DrawerItem.MULTISIG -> navController.popBackStack()
                                DrawerItem.SETTINGS -> navController.navigate(WalletRoutes.Settings)
                            }
                        }
                    )
                }
            ) {
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
        }

        composable(WalletRoutes.ImportWallet) { backStackEntry ->
            val viewModel: ImportWalletViewModel = viewModel()
            val state by viewModel.uiState.collectAsState()

            // Pick up descriptor from UR scanner on return.
            val scannedDescriptor by backStackEntry.savedStateHandle
                .getStateFlow<String?>("ur_descriptor", null)
                .collectAsState()
            LaunchedEffect(scannedDescriptor) {
                val d = scannedDescriptor
                if (d != null) {
                    viewModel.onDescriptorScanned(d)
                    backStackEntry.savedStateHandle.remove<String>("ur_descriptor")
                }
            }

            ImportWalletScreen(
                state = state,
                onClose = { navController.popBackStack() },
                onDescriptorChanged = viewModel::onDescriptorChanged,
                onWalletNameChanged = viewModel::onWalletNameChanged,
                onFileContent = viewModel::onDescriptorScanned,
                onScanQr = { navController.navigate(WalletRoutes.UrQrScanner) },
                onImport = {
                    viewModel.importWallet {
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set("wallet_imported", true)
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
                onPsbtClick = { psbt ->
                    navController.navigate(WalletRoutes.psbtDetail(psbt.id))
                }
            )
        }

        composable(WalletRoutes.CreatePsbt) { backStackEntry ->
            val walletId = backStackEntry.arguments?.getString("walletId") ?: ""

            // Snapshot the previous walletId so we can restore it on exit.
            val previousWalletId = remember { SessionStore.activeWalletId }

            // Swap activeWalletId synchronously via remember() — it runs
            // before viewModel() so ViewModel.init() picks the right wallet.
            // LaunchedEffect would run after init() and the VM would see
            // the old id.
            remember(walletId) { SessionStore.activeWalletId = walletId }

            val viewModel: SendTransactionViewModel = viewModel()
            val sendState by viewModel.uiState.collectAsState()

            // Restore the previous walletId when the user leaves this composable.
            DisposableEffect(walletId) {
                onDispose { SessionStore.activeWalletId = previousWalletId }
            }

            // QR scan result
            val qrAddress by backStackEntry.savedStateHandle
                .getStateFlow<String?>("qr_address", null)
                .collectAsState()
            LaunchedEffect(qrAddress) {
                val addr = qrAddress
                if (addr != null) {
                    viewModel.onRecipientChanged(addr)
                    backStackEntry.savedStateHandle.remove<String>("qr_address")
                }
            }

            SendTransactionScreen(
                state = sendState,
                title = "New PSBT",
                buttonText = "Create PSBT",
                onClose = { navController.popBackStack() },
                onRecipientChanged = viewModel::onRecipientChanged,
                onAmountChanged = viewModel::onAmountChanged,
                onAmountUnitToggled = viewModel::onAmountUnitToggled,
                onFeePriorityChanged = viewModel::onFeePriorityChanged,
                onAutoSelectChanged = viewModel::onAutoSelectChanged,
                onCustomFeeRateChanged = viewModel::onCustomFeeRateChanged,
                onScanQr = { navController.navigate(WalletRoutes.QrScanner) },
                onEditSelection = {
                    navController.navigate(WalletRoutes.CoinControl)
                },
                onCreateTransaction = {
                    viewModel.createTransaction(
                        onError = { errorMsg ->
                            navController.navigate(
                                WalletRoutes.transactionError(errorMsg)
                            ) {
                                popUpTo(WalletRoutes.Dashboard) { inclusive = false }
                            }
                        }
                    ) { _, _ ->
                        // PSBT is persisted — every cosigner will see it in their list.
                        navController.popBackStack()
                    }
                },
                onCancelTrezorWait = { viewModel.cancelTrezorWait() }
            )
        }

        composable(WalletRoutes.PsbtDetail) { backStackEntry ->
            val psbtId = backStackEntry.arguments?.getString("psbtId") ?: ""

            val viewModel: PsbtDetailViewModel = viewModel()
            val detailState by viewModel.uiState.collectAsState()
            val context = LocalContext.current
            val trezorLauncher = TrezorDeeplinkLauncher()
            val walletNetwork = remember {
                SessionStore.session?.user?.wallets
                    ?.find { it.id == SessionStore.activeWalletId }?.network ?: "testnet"
            }

            LaunchedEffect(psbtId) {
                viewModel.loadPsbt(psbtId)
            }

            // Observe as StateFlow — also fires on return via onNewIntent (FLAG_SINGLE_TOP).
            val pendingSignedPsbt by SessionStore.pendingSignedPsbt.collectAsState()
            LaunchedEffect(pendingSignedPsbt) {
                // Only process callbacks explicitly claimed by the PSBT detail flow.
                if (SessionStore.activeSignFlow.value != com.example.bitcoinwallet.core.session.SignFlow.PSBT_DETAIL) {
                    return@LaunchedEffect
                }
                // The user can navigate between PSBT detail screens while a
                // Trezor sign is in flight; without this guard we'd consume
                // the callback against whichever PSBT happens to be on
                // screen, submitting the wrong signature against the wrong
                // tx hash. Bail when the pinned psbtId doesn't match —
                // leave pendingSignedPsbt for the right screen to consume
                // when the user navigates back to it.
                val expected = SessionStore.pendingSignPsbtId
                if (expected != null && expected != psbtId) {
                    Log.d("WalletNav", "PSBT detail: ignoring callback for $expected while viewing $psbtId")
                    return@LaunchedEffect
                }
                val signedData = pendingSignedPsbt
                when {
                    signedData == "CANCELLED" -> {
                        SessionStore.setPendingSignedPsbt(null)
                        SessionStore.setActiveSignFlow(null)
                        SessionStore.pendingSignPsbtId = null
                        viewModel.onTrezorCancelled()
                    }
                    signedData == "WRONG_DEVICE" -> {
                        SessionStore.setPendingSignedPsbt(null)
                        SessionStore.setActiveSignFlow(null)
                        SessionStore.pendingSignPsbtId = null
                        SessionStore.setPendingLogoutReason(
                            "Signed out for security: connected Trezor did not match the one you signed in with."
                        )
                        SessionStore.clearAuth()
                        navController.navigate(
                            com.example.bitcoinwallet.feature.trezorconnect.navigation.TrezorRoutes.Connect
                        ) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                    signedData == "ERROR" -> {
                        SessionStore.setPendingSignedPsbt(null)
                        SessionStore.setActiveSignFlow(null)
                        SessionStore.pendingSignPsbtId = null
                        viewModel.onTrezorFailed("Trezor returned an invalid response. Please try again.")
                    }
                    signedData != null -> {
                        SessionStore.setPendingSignedPsbt(null)
                        SessionStore.setActiveSignFlow(null)
                        SessionStore.pendingSignPsbtId = null
                        viewModel.onTrezorSigned(signedData)
                    }
                }
            }

            // No auto-timeout: see comment in Send route. The wait holds
            // indefinitely until either the deeplink callback arrives or the
            // user clicks "Cancel signing", which clears pendingRequestId so
            // any post-cancel callback is rejected by TrezorCallbackActivity.

            // Navigate to TransactionSent after successful broadcast.
            // Reset the flag so a recomposition or re-entry does not re-navigate.
            LaunchedEffect(detailState.broadcastSuccess) {
                if (detailState.broadcastSuccess) {
                    viewModel.consumeBroadcastSuccess()
                    navController.navigate(
                        WalletRoutes.transactionSent(
                            detailState.totalOutputSats,
                            detailState.estimatedFeeSats,
                            detailState.walletId
                        )
                    ) {
                        popUpTo(WalletRoutes.Dashboard) { inclusive = false }
                    }
                }
            }

            PsbtDetailScreen(
                state = detailState,
                onClose = { navController.popBackStack() },
                onSignPsbt = {
                    // Claim the sign callback for the PSBT detail flow before
                    // launching Trezor. Pin the psbtId too so the LaunchedEffect
                    // handler (above) can reject a callback that arrives while
                    // the user is viewing a different PSBT detail screen — the
                    // signed data would otherwise be submitted via the wrong
                    // viewmodel against the wrong tx hash.
                    SessionStore.setActiveSignFlow(com.example.bitcoinwallet.core.session.SignFlow.PSBT_DETAIL)
                    SessionStore.pendingSignPsbtId = detailState.psbtId
                    viewModel.markAwaitingTrezor()
                    val params = detailState.trezorConnectParams
                    val launched = if (params != null) {
                        Log.d("WalletNav", "PSBT detail: signing with structured params")
                        trezorLauncher.openSignTransactionStructured(context, params)
                    } else {
                        Log.d("WalletNav", "PSBT detail: signing with raw PSBT fallback")
                        trezorLauncher.openSignTransaction(context, detailState.psbtBase64, walletNetwork)
                    }
                    if (!launched) {
                        SessionStore.setActiveSignFlow(null)
                        SessionStore.pendingSignPsbtId = null
                        viewModel.onTrezorFailed("Trezor Suite is not installed or cannot be launched.")
                    }
                },
                onBroadcast = {
                    viewModel.broadcast()
                },
                onShowRecipients = {
                    viewModel.showSignersDialog()
                },
                onDismissRecipients = {
                    viewModel.dismissSignersDialog()
                },
                onRenameCosigner = { idx, label ->
                    viewModel.updateCosignerLabel(idx, label)
                },
                onCancelPsbt = {
                    // Delete the draft on the backend (which releases the
                    // reserved UTXOs) and pop back to the PSBT list.
                    viewModel.cancelPsbt(onSuccess = {
                        navController.popBackStack()
                    })
                },
                onCancelTrezorWait = { viewModel.cancelTrezorWait() }
            )
        }

        composable(WalletRoutes.Settings) {
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val scope = rememberCoroutineScope()

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    DrawerContent(
                        onItemClick = { item ->
                            scope.launch { drawerState.close() }
                            when (item) {
                                DrawerItem.HOME -> navController.popBackStack(WalletRoutes.Dashboard, inclusive = false)
                                DrawerItem.MULTISIG -> navController.navigate(WalletRoutes.MultisigWallets)
                                DrawerItem.SETTINGS -> { /* already here */ }
                            }
                        }
                    )
                }
            ) {
                SettingsScreen(
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onSwitchAccount = {
                        // Keep the JWT session alive — only the wallet selection is cleared
                        // so the user lands on SelectAccount and can switch to another
                        // BIP-48 account on the same Trezor without re-authenticating.
                        // activeAccountIndex is intentionally kept so SelectAccount can
                        // pre-select the account the user is switching away from.
                        SessionStore.activeWalletId = null
                        navController.navigate(
                            com.example.bitcoinwallet.feature.trezorconnect.navigation.TrezorRoutes.SelectAccount
                        ) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
        }

        composable(WalletRoutes.QrScanner) {
            QrScannerScreen(
                onResult = { address ->
                    // Stash the scanned address in the previous destination's
                    // savedStateHandle (Send or CreatePsbt picks it up from there).
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("qr_address", address)
                    navController.popBackStack()
                },
                onClose = { navController.popBackStack() }
            )
        }

        composable(WalletRoutes.UrQrScanner) {
            UrQrScannerScreen(
                onResult = { descriptor ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("ur_descriptor", descriptor)
                    navController.popBackStack()
                },
                onClose = { navController.popBackStack() }
            )
        }
    }
}
