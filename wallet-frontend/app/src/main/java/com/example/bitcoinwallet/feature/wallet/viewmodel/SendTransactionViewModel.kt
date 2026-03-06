package com.example.bitcoinwallet.feature.wallet.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bitcoinwallet.core.api.FeeEstimatesDto
import com.example.bitcoinwallet.core.api.TrezorConnectParamsDto
import com.example.bitcoinwallet.core.api.UtxoSelectionDto
import com.example.bitcoinwallet.core.api.WalletApi
import com.example.bitcoinwallet.core.session.SessionStore
import com.example.bitcoinwallet.core.session.SignResultType
import com.example.bitcoinwallet.core.signer.WalletType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "SendTransactionVM"

/**
 * Represents a UTXO selected through Coin Control.
 */
data class SelectedUtxoInfo(
    val txid: String,
    val vout: Int,
    val valueSats: Long,
    val address: String
) {
    val key: String get() = "$txid:$vout"
}

/**
 * Fee priority level — maps to different fee rate estimates.
 */
enum class FeePriority {
    LOW,
    MEDIUM,
    HIGH
}

/**
 * UI State for the Send Transaction screen.
 */
data class SendTransactionUiState(
    val recipientAddress: String = "",
    val amountBtc: String = "",
    val feePriority: FeePriority = FeePriority.MEDIUM,
    val autoSelect: Boolean = true,
    val customFeeRate: String = "",   // sat/vB — used when autoSelect is off
    val selectedUtxoCount: Int = 0,   // how many UTXOs manually selected
    val selectedUtxos: List<SelectedUtxoInfo> = emptyList(),

    // Loaded data
    val balanceSats: Long = 0L,
    val feeEstimates: FeeEstimatesDto? = null,

    // Computed summary
    val amountSats: Long = 0L,
    val feeSats: Long = 0L,
    val feeRateSatVb: Double = 0.0,   // effective fee rate (sat/vB) for summary display
    val totalSats: Long = 0L,
    val remainingSats: Long = 0L,

    // State flags
    val isLoading: Boolean = true,
    val isSending: Boolean = false,
    val error: String? = null,
    val txCreatedId: String? = null,
    val psbtBase64: String? = null,      // PSBT to be signed by Trezor
    val trezorConnectParams: TrezorConnectParamsDto? = null, // structured params for Trezor Connect
    val awaitingTrezor: Boolean = false,  // waiting for Trezor callback
    val isSubmitting: Boolean = false,    // submitting signed PSBT to backend
    val broadcastSuccess: Boolean = false,
    val broadcastTxid: String? = null,

    // Validation
    val addressError: String? = null,
    val amountError: String? = null
)

/**
 * ViewModel for the Send BTC screen.
 * Handles fee estimation, amount computation, and PSBT creation.
 */
class SendTransactionViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SendTransactionUiState())
    val uiState: StateFlow<SendTransactionUiState> = _uiState.asStateFlow()

    private val repository = WalletApi.repository

    init {
        loadInitialData()
    }

    /**
     * Loads wallet balance and fee estimates in parallel.
     */
    private fun loadInitialData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val accessToken = SessionStore.session?.accessToken
            val walletId = SessionStore.activeWalletId

            if (accessToken == null || walletId == null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "No active session"
                )
                return@launch
            }

            try {
                val balance = repository.getWalletBalance(walletId, accessToken)
                val fees = repository.getFeeEstimates(accessToken)

                Log.d(TAG, "Balance: ${balance.balanceSats} sats, fees: fast=${fees.fastestFee} med=${fees.halfHourFee} low=${fees.hourFee}")

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    balanceSats = balance.balanceSats,
                    feeEstimates = fees
                )
                recalculate()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load initial data", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load data"
                )
            }
        }
    }

    // ========== User Actions ==========

    fun onRecipientChanged(address: String) {
        _uiState.value = _uiState.value.copy(
            recipientAddress = address.trim(),
            addressError = null,
            txCreatedId = null
        )
    }

    fun onAmountChanged(amount: String) {
        // Allow only valid BTC decimal input
        val filtered = amount.filter { it.isDigit() || it == '.' }
        _uiState.value = _uiState.value.copy(
            amountBtc = filtered,
            amountError = null,
            txCreatedId = null
        )
        recalculate()
    }

    fun onFeePriorityChanged(priority: FeePriority) {
        _uiState.value = _uiState.value.copy(feePriority = priority)
        recalculate()
    }

    fun onAutoSelectChanged(autoSelect: Boolean) {
        _uiState.value = _uiState.value.copy(autoSelect = autoSelect)
        recalculate()
    }

    fun onCustomFeeRateChanged(rate: String) {
        val filtered = rate.filter { it.isDigit() || it == '.' }
        _uiState.value = _uiState.value.copy(customFeeRate = filtered)
        recalculate()
    }

    /**
     * Called from CoinControl screen when user confirms UTXO selection.
     */
    fun onUtxosSelected(utxos: List<SelectableUtxo>) {
        val mapped = utxos.map {
            SelectedUtxoInfo(txid = it.txid, vout = it.vout, valueSats = it.valueSats, address = it.address)
        }
        _uiState.value = _uiState.value.copy(
            selectedUtxos = mapped,
            selectedUtxoCount = mapped.size
        )
        recalculate()
    }

    /**
     * Returns the set of currently selected UTXO keys for pre-selection in CoinControl.
     */
    fun getSelectedUtxoKeys(): Set<String> =
        _uiState.value.selectedUtxos.map { it.key }.toSet()

    /**
     * Create the PSBT transaction on the backend.
     * After creation, signals the caller to open Trezor for signing.
     *
     * @param onPsbtReady Called with psbtBase64 and optional Trezor Connect params.
     *        For singlesig, trezorParams != null and should be used for Trezor deeplink.
     */
    fun createTransaction(onPsbtReady: (psbtBase64: String, trezorParams: TrezorConnectParamsDto?) -> Unit) {
        val state = _uiState.value

        // Validate
        if (!validateInputs()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true, error = null)

            val accessToken = SessionStore.session?.accessToken
            val walletId = SessionStore.activeWalletId

            if (accessToken == null || walletId == null) {
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    error = "No active session"
                )
                return@launch
            }

            try {
                val feeRate = getSelectedFeeRate()

                // In manual mode, pass selected UTXOs to backend
                val utxoSelection = if (!state.autoSelect && state.selectedUtxos.isNotEmpty()) {
                    state.selectedUtxos.map { UtxoSelectionDto(txid = it.txid, vout = it.vout) }
                } else null

                Log.d(TAG, "Creating PSBT: to=${state.recipientAddress}, amount=${state.amountSats} sats, feeRate=$feeRate sat/vB, utxos=${utxoSelection?.size ?: "auto"}")

                // Extract account index for multisig cosigner matching.
                // Multisig wallet IDs (w-{hash}) don't contain a numeric suffix,
                // so for multisig we look up the singlesig wallet's account index instead.
                val wallet = SessionStore.session?.user?.wallets?.find { it.id == walletId }
                val signerAccount = if (wallet?.type == WalletType.MULTI_SIG) {
                    SessionStore.session?.user?.wallets
                        ?.firstOrNull { it.type == WalletType.SINGLE_SIG }
                        ?.id?.split("-")?.lastOrNull()?.toIntOrNull() ?: 0
                } else {
                    walletId?.split("-")?.lastOrNull()?.toIntOrNull()
                }

                val response = WalletApi.client.createPsbt(
                    accessToken = accessToken,
                    walletId = walletId,
                    destinationAddress = state.recipientAddress,
                    amountSats = state.amountSats,
                    feeRate = feeRate,
                    utxos = utxoSelection,
                    signerAccountIndex = signerAccount
                )

                Log.d(TAG, "PSBT created: id=${response.id}, fee=${response.estimatedFee} sats, vsize=${response.estimatedVsize}, trezorConnect=${response.trezorConnectParams != null}")

                // Detailní log TrezorConnectParams pro debugging
                response.trezorConnectParams?.let { tcp ->
                    Log.d(TAG, "=== TrezorConnectParams ===")
                    Log.d(TAG, "  coin=${tcp.coin}, version=${tcp.version}, locktime=${tcp.locktime}")
                    Log.d(TAG, "  inputs (${tcp.inputs.size}):")
                    tcp.inputs.forEachIndexed { i, inp ->
                        Log.d(TAG, "    [$i] address_n=${inp.address_n}, prev_hash=${inp.prev_hash}, prev_index=${inp.prev_index}, amount=${inp.amount}, script_type=${inp.script_type}, sequence=${inp.sequence}")
                    }
                    Log.d(TAG, "  outputs (${tcp.outputs.size}):")
                    tcp.outputs.forEachIndexed { i, out ->
                        Log.d(TAG, "    [$i] address=${out.address}, address_n=${out.address_n}, amount=${out.amount}, script_type=${out.script_type}")
                    }
                    Log.d(TAG, "  refTxs=${tcp.refTxs?.size ?: "null"}")
                    tcp.inputs.forEach { inp ->
                        inp.multisig?.let { ms ->
                            Log.d(TAG, "  multisig: m=${ms.m} pubkeys=${ms.pubkeys.size} sigs=${ms.signatures}")
                            ms.pubkeys.forEachIndexed { j, pk ->
                                Log.d(TAG, "    pubkey[$j] node=HDNode(depth=${pk.node.depth}, pubkey=${pk.node.public_key.take(16)}...) address_n=${pk.address_n}")
                            }
                        }
                    }
                    Log.d(TAG, "=== End TrezorConnectParams ===")
                }

                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    txCreatedId = response.id,
                    psbtBase64 = response.psbtBase64,
                    trezorConnectParams = response.trezorConnectParams,
                    feeSats = response.estimatedFee,
                    awaitingTrezor = true
                )

                // Signal caller to open Trezor deeplink
                onPsbtReady(response.psbtBase64, response.trezorConnectParams)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create PSBT", e)
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    error = e.message ?: "Failed to create transaction"
                )
            }
        }
    }

    /**
     * Dispatch na správný handler podle typu Trezor odpovědi a typu peněženky.
     */
    fun onTrezorResult(
        signedData: String,
        signType: SignResultType,
        onBroadcastSuccess: () -> Unit
    ) {
        val wallet = SessionStore.session?.user?.wallets?.find { it.id == SessionStore.activeWalletId }
        val isMultisig = wallet?.type == WalletType.MULTI_SIG

        when {
            // Multisig: submit Trezor signatures to backend (don't broadcast directly)
            isMultisig && signType == SignResultType.SERIALIZED_TX -> {
                onTrezorMultisigSigned(signedData, onBroadcastSuccess)
            }
            // Singlesig: broadcast serialized tx directly
            signType == SignResultType.SERIALIZED_TX -> {
                onTrezorSerializedTx(signedData, onBroadcastSuccess)
            }
            // Fallback: signed PSBT flow
            else -> onTrezorSigned(signedData, onBroadcastSuccess)
        }
    }

    /**
     * Handle Trezor Connect response for multisig wallet.
     * Submits per-input signatures to backend via /sign-trezor endpoint.
     * If enough signatures, broadcasts; otherwise shows status.
     */
    private fun onTrezorMultisigSigned(serializedTxHex: String, onBroadcastSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                awaitingTrezor = false,
                isSubmitting = true,
                error = null
            )

            val accessToken = SessionStore.session?.accessToken
            val psbtId = _uiState.value.txCreatedId
            val fingerprint = SessionStore.session?.user?.trezorFingerprint ?: "unknown"
            val trezorSigs = SessionStore.pendingTrezorSignatures.value

            if (accessToken == null || psbtId == null) {
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    error = "No active session or PSBT"
                )
                return@launch
            }

            try {
                if (trezorSigs != null && trezorSigs.isNotEmpty()) {
                    // Submit per-input signatures via sign-trezor endpoint
                    Log.d(TAG, "Submitting ${trezorSigs.size} Trezor signatures for multisig PSBT...")
                    val result = WalletApi.client.signTrezor(
                        psbtId = psbtId,
                        accessToken = accessToken,
                        signatures = trezorSigs,
                        cosignerIndex = 0,  // TODO: let user pick cosigner
                        fingerprint = fingerprint,
                        serializedTx = serializedTxHex
                    )
                    SessionStore.setPendingTrezorSignatures(null)

                    if (result.currentSigs >= result.requiredSigs) {
                        // Enough signatures — try to broadcast serializedTx directly
                        Log.d(TAG, "Multisig fully signed (${result.currentSigs}/${result.requiredSigs}), broadcasting...")
                        val broadcastResp = WalletApi.client.broadcastRawTx(
                            psbtId = psbtId,
                            accessToken = accessToken,
                            txHex = serializedTxHex
                        )
                        _uiState.value = _uiState.value.copy(
                            isSubmitting = false,
                            broadcastSuccess = true,
                            broadcastTxid = broadcastResp.txid
                        )
                        onBroadcastSuccess()
                    } else {
                        // Need more signatures
                        Log.d(TAG, "Multisig needs more signatures: ${result.currentSigs}/${result.requiredSigs}")
                        _uiState.value = _uiState.value.copy(
                            isSubmitting = false,
                            error = "Podpis přidán (${result.currentSigs}/${result.requiredSigs}). Potřeba dalších podpisů."
                        )
                    }
                } else {
                    // No per-input signatures — try broadcast-raw as fallback
                    Log.w(TAG, "No Trezor signatures array, attempting broadcast-raw...")
                    val broadcastResp = WalletApi.client.broadcastRawTx(
                        psbtId = psbtId,
                        accessToken = accessToken,
                        txHex = serializedTxHex
                    )
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        broadcastSuccess = true,
                        broadcastTxid = broadcastResp.txid
                    )
                    onBroadcastSuccess()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process multisig signing", e)
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    error = e.message ?: "Failed to process multisig signing"
                )
            }
        }
    }

    /**
     * Handle raw serialized tx from Trezor Connect.
     * Skip addSignature/finalize — broadcast directly.
     */
    private fun onTrezorSerializedTx(serializedTxHex: String, onBroadcastSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                awaitingTrezor = false,
                isSubmitting = true,
                error = null
            )

            val accessToken = SessionStore.session?.accessToken
            val psbtId = _uiState.value.txCreatedId

            if (accessToken == null || psbtId == null) {
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    error = "No active session or PSBT"
                )
                return@launch
            }

            try {
                Log.d(TAG, "Broadcasting raw serialized tx from Trezor (${serializedTxHex.length} chars hex)...")
                val broadcastResp = WalletApi.client.broadcastRawTx(
                    psbtId = psbtId,
                    accessToken = accessToken,
                    txHex = serializedTxHex
                )

                Log.d(TAG, "Transaction broadcast! txid=${broadcastResp.txid}")

                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    broadcastSuccess = true,
                    broadcastTxid = broadcastResp.txid
                )

                onBroadcastSuccess()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast raw tx", e)
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    error = e.message ?: "Failed to broadcast transaction"
                )
            }
        }
    }

    /**
     * Called when Trezor returns a signed PSBT (legacy/multisig flow).
     * Submits the signature to backend, then finalizes and broadcasts.
     *
     * @param signedPsbtBase64 The PSBT signed by Trezor.
     * @param onBroadcastSuccess Called when tx is successfully broadcast.
     */
    fun onTrezorSigned(signedPsbtBase64: String, onBroadcastSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                awaitingTrezor = false,
                isSubmitting = true,
                error = null
            )

            val accessToken = SessionStore.session?.accessToken
            val psbtId = _uiState.value.txCreatedId
            val fingerprint = SessionStore.session?.user?.trezorFingerprint ?: "unknown"

            if (accessToken == null || psbtId == null) {
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    error = "No active session or PSBT"
                )
                return@launch
            }

            try {
                // 1. Submit signed PSBT to backend
                Log.d(TAG, "Submitting signed PSBT to backend...")
                WalletApi.client.addSignature(
                    psbtId = psbtId,
                    accessToken = accessToken,
                    signedPsbtBase64 = signedPsbtBase64,
                    deviceId = fingerprint,   // byl hardcoded "trezor"; použij fingerprint jako identifikátor
                    fingerprint = fingerprint
                )

                // 2. Finalize
                Log.d(TAG, "Finalizing PSBT...")
                WalletApi.client.finalizePsbt(psbtId, accessToken)

                // 3. Broadcast
                Log.d(TAG, "Broadcasting transaction...")
                val broadcastResp = WalletApi.client.broadcastPsbt(psbtId, accessToken)

                Log.d(TAG, "Transaction broadcast! txid=${broadcastResp.txid}")

                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    broadcastSuccess = true,
                    broadcastTxid = broadcastResp.txid
                )

                onBroadcastSuccess()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to submit/finalize/broadcast", e)
                _uiState.value = _uiState.value.copy(
                    isSubmitting = false,
                    error = e.message ?: "Failed to broadcast transaction"
                )
            }
        }
    }

    // ========== Internal ==========

    private fun recalculate() {
        val state = _uiState.value
        val btcAmount = state.amountBtc.toDoubleOrNull() ?: 0.0
        val amountSats = (btcAmount * 100_000_000).toLong()

        // Estimate vsize based on wallet type (1-in, 2-out):
        //   P2WPKH (singlesig):  ~141 vbytes
        //   P2WSH m-of-n:        (base(113)*4 + witness(5 + 73m + 34n)) / 4
        val walletId = SessionStore.activeWalletId
        val wallet = SessionStore.session?.user?.wallets?.find { it.id == walletId }
        val estimatedVsize = if (wallet?.type == WalletType.MULTI_SIG) {
            val m = wallet.m ?: 2
            val n = wallet.n ?: 3
            (457 + 73 * m + 34 * n) / 4 + 1
        } else {
            141
        }
        val feeRate = getSelectedFeeRate()
        val feeSats = (estimatedVsize * feeRate).toLong()

        val totalSats = amountSats + feeSats

        // In manual UTXO mode, show remaining from selected UTXOs (= change output)
        val availableSats = if (!state.autoSelect && state.selectedUtxos.isNotEmpty()) {
            state.selectedUtxos.sumOf { it.valueSats }
        } else {
            state.balanceSats
        }
        val remainingSats = availableSats - totalSats

        _uiState.value = state.copy(
            amountSats = amountSats,
            feeSats = feeSats,
            feeRateSatVb = feeRate,
            totalSats = totalSats,
            remainingSats = maxOf(remainingSats, 0L)
        )
    }

    private fun getSelectedFeeRate(): Double {
        val state = _uiState.value
        // In manual UTXO mode, use custom fee rate only if the user actually entered one
        if (!state.autoSelect) {
            val customRate = state.customFeeRate.toDoubleOrNull()
            if (customRate != null && customRate > 0) return customRate
        }
        // Fall back to preset priorities (also used when autoSelect=false but no custom rate entered)
        val fees = state.feeEstimates ?: return 5.0
        return when (state.feePriority) {
            FeePriority.LOW -> fees.hourFee.toDouble()
            FeePriority.MEDIUM -> fees.halfHourFee.toDouble()
            FeePriority.HIGH -> fees.fastestFee.toDouble()
        }
    }

    private fun validateInputs(): Boolean {
        val state = _uiState.value
        var hasError = false

        // Address validation
        val addr = state.recipientAddress
        if (addr.isBlank()) {
            _uiState.value = _uiState.value.copy(addressError = "Address is required")
            hasError = true
        } else if (!isValidBitcoinAddress(addr)) {
            _uiState.value = _uiState.value.copy(addressError = "Invalid Bitcoin address")
            hasError = true
        }

        // Amount validation
        if (state.amountSats <= 0) {
            _uiState.value = _uiState.value.copy(amountError = "Enter a valid amount")
            hasError = true
        } else if (state.amountSats < 546) {
            _uiState.value = _uiState.value.copy(amountError = "Amount below dust limit (546 sats)")
            hasError = true
        } else if (!state.autoSelect && state.selectedUtxos.isNotEmpty()) {
            // Manual UTXO mode: check selected UTXOs cover amount + fee
            val selectedTotal = state.selectedUtxos.sumOf { it.valueSats }
            if (selectedTotal < state.totalSats) {
                val shortfall = state.totalSats - selectedTotal
                val shortfallBtc = String.format("%.8f", shortfall / 100_000_000.0)
                _uiState.value = _uiState.value.copy(
                    amountError = "Selected UTXOs insufficient (short $shortfallBtc BTC)"
                )
                hasError = true
            }
        } else if (state.totalSats > state.balanceSats) {
            _uiState.value = _uiState.value.copy(amountError = "Insufficient balance")
            hasError = true
        }

        return !hasError
    }

    private fun isValidBitcoinAddress(address: String): Boolean {
        // Basic validation: mainnet/testnet/signet address formats
        return address.startsWith("bc1") ||
                address.startsWith("tb1") ||
                address.startsWith("1") ||
                address.startsWith("3") ||
                address.startsWith("m") ||
                address.startsWith("n") ||
                address.startsWith("2")
    }
}
