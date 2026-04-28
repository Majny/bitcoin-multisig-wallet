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

/*
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

/*
 * Fee priority level — maps to different fee rate estimates.
 */
enum class FeePriority {
    LOW,
    MEDIUM,
    HIGH
}

/*
 * Unit the user is entering the amount in. FIAT uses [SendTransactionUiState.fiatCurrency]
 * (CZK/USD/EUR, picked from settings). BTC is the canonical unit — sats are
 * always derived from [SendTransactionUiState.amountInput] so transaction value
 * does not drift as the exchange rate moves.
 */
enum class AmountUnit { BTC, FIAT }

/*
 * UI State for the Send Transaction screen.
 */
data class SendTransactionUiState(
    val recipientAddress: String = "",
    // Raw text the user typed. Interpreted in [amountUnit]. BTC is the source of
    // truth for [amountSats]; when the user enters FIAT we convert via
    // [btcFiatRate] but never round-trip — flipping the unit keeps the
    // already-committed BTC value untouched so the satoshi amount is stable.
    val amountInput: String = "",
    val amountUnit: AmountUnit = AmountUnit.BTC,
    val fiatCurrency: String = "CZK",
    val btcFiatRate: Double? = null,   // price of 1 BTC in [fiatCurrency]
    val feePriority: FeePriority = FeePriority.MEDIUM,
    val autoSelect: Boolean = true,
    val customFeeRate: String = "",   // sat/vB — used when autoSelect is off
    val selectedUtxoCount: Int = 0,   // how many UTXOs manually selected
    val selectedUtxos: List<SelectedUtxoInfo> = emptyList(),

    // Loaded data
    val balanceSats: Long = 0L,
    val reservedSats: Long = 0L,
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
    val signerCosignerIndex: Int = 0,       // resolved cosigner index from createPsbt response
    val awaitingTrezor: Boolean = false,  // waiting for Trezor callback
    val isSubmitting: Boolean = false,    // submitting signed PSBT to backend
    val broadcastSuccess: Boolean = false,
    val broadcastTxid: String? = null,

    // Validation
    val addressError: String? = null,
    val amountError: String? = null
)

/*
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

    /*
     * Loads wallet balance, fee estimates, and BTC/fiat rate in parallel.
     */
    private fun loadInitialData() {
        viewModelScope.launch {
            val currency = SessionStore.preferredCurrency.value.uppercase()
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                fiatCurrency = currency
            )

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
                val fees = try {
                    repository.getFeeEstimates(accessToken)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch fee estimates — user must enter a custom fee rate", e)
                    null
                }

                // Fetch BTC price in the user's preferred currency. If price-service
                // is down we simply disable fiat input (the toggle falls back to BTC).
                val rate = try {
                    val prices = WalletApi.client.getBitcoinPrices(accessToken, "czk,usd,eur")
                    when (currency) {
                        "USD" -> prices.usd
                        "EUR" -> prices.eur
                        else -> prices.czk
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch BTC price — fiat input will be disabled", e)
                    null
                }

                // How many sats are locked by pending PSBTs? Reserved amount =
                // sum of INPUT UTXOs (the whole UTXO is locked until the tx
                // confirms — change only reappears as a new UTXO).
                val reserved = try {
                    val psbts = WalletApi.client.listPsbtsForWallet(walletId, accessToken)
                    psbts.psbts
                        .filter { it.status == "pending" || it.status == "signed" }
                        .sumOf { psbt ->
                            psbt.trezorConnectParams?.inputs
                                ?.sumOf { it.amount.toLongOrNull() ?: 0L }
                                ?: 0L
                        }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to calculate reserved sats", e)
                    0L
                }

                if (fees != null) {
                    Log.d(TAG, "Balance: ${balance.balanceSats} sats, reserved: $reserved sats, fees: fast=${fees.fastestFee} med=${fees.halfHourFee} low=${fees.hourFee}")
                } else {
                    Log.d(TAG, "Balance: ${balance.balanceSats} sats, reserved: $reserved sats, fees: unavailable")
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    balanceSats = balance.balanceSats,
                    reservedSats = reserved,
                    feeEstimates = fees,
                    btcFiatRate = rate
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

    /*
     * Resets Trezor-related state when signing is cancelled or times out.
     * Call this when user returns from Trezor without a result.
     */
    fun resetTrezorState() {
        _uiState.value = _uiState.value.copy(
            awaitingTrezor = false,
            isSending = false,
            error = null
        )
    }

    /*
     * Called when Trezor Suite returned a failure that is NOT a user cancel
     * (malformed JSON, protocol error, etc.). Shows a distinct error.
     */
    fun onTrezorFailed(message: String) {
        _uiState.value = _uiState.value.copy(
            awaitingTrezor = false,
            isSending = false,
            isSubmitting = false,
            error = message
        )
    }

    /* Called by the UI after it has acted on broadcastSuccess (navigated away). */
    fun consumeBroadcastSuccess() {
        if (_uiState.value.broadcastSuccess) {
            _uiState.value = _uiState.value.copy(broadcastSuccess = false)
        }
    }

    fun onRecipientChanged(address: String) {
        _uiState.value = _uiState.value.copy(
            recipientAddress = address.trim(),
            addressError = null,
            txCreatedId = null
        )
    }

    fun onAmountChanged(amount: String) {
        val filtered = amount.filter { it.isDigit() || it == '.' }
        _uiState.value = _uiState.value.copy(
            amountInput = filtered,
            amountError = null,
            txCreatedId = null
        )
        recalculate()
    }

    /*
     * Flip the entry unit between BTC and the user's fiat currency.
     * Converts the current input so the sats value stays stable across the
     * toggle — the user sees the same amount denominated differently,
     * not a reset field.
     */
    fun onAmountUnitToggled() {
        val state = _uiState.value
        val rate = state.btcFiatRate
        if (rate == null || rate <= 0.0) return   // no rate → fiat input disabled

        val newUnit = if (state.amountUnit == AmountUnit.BTC) AmountUnit.FIAT else AmountUnit.BTC
        val current = state.amountInput.toDoubleOrNull()
        val converted = when {
            current == null || current == 0.0 -> state.amountInput
            newUnit == AmountUnit.FIAT -> formatFiatInput(current * rate)
            else -> formatBtcInput(current / rate)
        }
        _uiState.value = state.copy(
            amountUnit = newUnit,
            amountInput = converted,
            amountError = null,
            txCreatedId = null
        )
        recalculate()
    }

    private fun formatBtcInput(btc: Double): String =
        // 8 decimals covers satoshi precision; trailing zeros stripped for readability.
        String.format(java.util.Locale.US, "%.8f", btc).trimEnd('0').trimEnd('.')

    private fun formatFiatInput(fiat: Double): String =
        // 2 decimals matches the WalletBalance fiat display; no grouping so the
        // field text stays parseable as a decimal.
        String.format(java.util.Locale.US, "%.2f", fiat).trimEnd('0').trimEnd('.')

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

    /*
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

    /*
     * Returns the set of currently selected UTXO keys for pre-selection in CoinControl.
     */
    fun getSelectedUtxoKeys(): Set<String> =
        _uiState.value.selectedUtxos.map { it.key }.toSet()

    /*
     * Create the PSBT transaction on the backend.
     * After creation, signals the caller to open Trezor for signing.
     *
     * @param onPsbtReady Called with psbtBase64 and optional Trezor Connect params.
     *        For singlesig, trezorParams != null and should be used for Trezor deeplink.
     */
    fun createTransaction(
        onError: ((message: String) -> Unit)? = null,
        onPsbtReady: (psbtBase64: String, trezorParams: TrezorConnectParamsDto?) -> Unit
    ) {
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
                    state.selectedUtxos.map { UtxoSelectionDto(txid = it.txid, vout = it.vout, address = it.address) }
                } else null

                Log.d(TAG, "Creating PSBT: to=${state.recipientAddress}, amount=${state.amountSats} sats, feeRate=$feeRate sat/vB, utxos=${utxoSelection?.size ?: "auto"}")

                // Extract account index for multisig cosigner matching.
                val signerAccount = SessionStore.activeAccountIndex

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

                // Verbose TrezorConnectParams dump — keeps the deeplink
                // payload auditable when debugging firmware rejections.
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
                    signerCosignerIndex = response.signerCosignerIndex,
                    feeSats = response.estimatedFee,
                    awaitingTrezor = true
                )

                // Signal caller to open Trezor deeplink
                onPsbtReady(response.psbtBase64, response.trezorConnectParams)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create PSBT", e)
                val errorMsg = e.message ?: "Failed to create transaction"
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    error = errorMsg
                )
                onError?.invoke(errorMsg)
            }
        }
    }

    /*
     * Route the Trezor result to the right handler based on the response
     * type (serializedTx vs signed PSBT) and the wallet type (singlesig vs
     * multisig). Callers upstream don't need to know the distinction.
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
            else -> {
                onTrezorSerializedTx(signedData, onBroadcastSuccess)
            }
        }
    }

    /*
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
                    val signerAccountIdx = SessionStore.activeAccountIndex
                    val result = WalletApi.client.signTrezor(
                        psbtId = psbtId,
                        accessToken = accessToken,
                        signatures = trezorSigs,
                        cosignerIndex = _uiState.value.signerCosignerIndex,
                        fingerprint = fingerprint,
                        serializedTx = serializedTxHex,
                        signerAccountIndex = signerAccountIdx
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
                            error = "Signature added (${result.currentSigs}/${result.requiredSigs}). More signatures required."
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

    /*
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

    // ========== Internal ==========

    private fun recalculate() {
        val state = _uiState.value
        // Sats are always derived — user types in either BTC or fiat, we normalise here.
        val amountSats = when (state.amountUnit) {
            AmountUnit.BTC -> {
                val btc = state.amountInput.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                btc.multiply(java.math.BigDecimal("100000000")).toLong()
            }
            AmountUnit.FIAT -> {
                val fiat = state.amountInput.toDoubleOrNull() ?: 0.0
                val rate = state.btcFiatRate
                if (rate == null || rate <= 0.0) 0L
                else (fiat / rate * 100_000_000.0).toLong()
            }
        }

        // Estimate vsize for 1 input, 2 outputs (same formula as backend PsbtBuilder):
        //   overhead = 10, outputs = 31 * 2 = 62
        //   P2WPKH (singlesig) input: 68 vB → 140 total
        //   P2WSH m-of-n input: 41 + (5 + 73*m + 34*n)/4 vB (SegWit witness discount)
        val walletId = SessionStore.activeWalletId
        val wallet = SessionStore.session?.user?.wallets?.find { it.id == walletId }
        val inputVsize = if (wallet?.type == WalletType.MULTI_SIG) {
            val m = wallet.m ?: 2
            val n = wallet.n ?: 3
            41 + (5 + 73 * m + 34 * n) / 4
        } else {
            68
        }
        val estimatedVsize = 10 + 62 + inputVsize
        val feeRate = getSelectedFeeRate()
        val feeSats = (estimatedVsize * feeRate).toLong()

        val totalSats = amountSats + feeSats

        // In manual UTXO mode, show remaining from selected UTXOs (= change output)
        // In auto mode, exclude reserved sats (pending PSBTs)
        val availableSats = if (!state.autoSelect && state.selectedUtxos.isNotEmpty()) {
            state.selectedUtxos.sumOf { it.valueSats }
        } else {
            maxOf(state.balanceSats - state.reservedSats, 0L)
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
        // Fee estimates not loaded — return 0 so validation blocks send until
        // the user enters a custom rate or fees come back online.
        val fees = state.feeEstimates ?: return 0.0
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
        val walletNetwork = SessionStore.session?.user?.wallets
            ?.find { it.id == SessionStore.activeWalletId }?.network ?: "testnet"
        if (addr.isBlank()) {
            _uiState.value = _uiState.value.copy(addressError = "Address is required")
            hasError = true
        } else {
            val validationError = validateBitcoinAddress(addr, walletNetwork)
            if (validationError != null) {
                _uiState.value = _uiState.value.copy(addressError = validationError)
                hasError = true
            }
        }

        // Fee estimates must be available unless user provides a custom rate.
        if (state.feeEstimates == null && state.customFeeRate.toDoubleOrNull().let { it == null || it <= 0 }) {
            _uiState.value = _uiState.value.copy(
                amountError = "Fee estimates unavailable. Enter a custom fee rate to continue."
            )
            return false
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
                val shortfallBtc = String.format(java.util.Locale.US, "%.8f", shortfall / 100_000_000.0)
                _uiState.value = _uiState.value.copy(
                    amountError = "Selected UTXOs insufficient (short $shortfallBtc BTC)"
                )
                hasError = true
            }
        } else if (state.autoSelect && state.reservedSats > 0 && state.totalSats > state.balanceSats - state.reservedSats) {
            val reservedBtc = String.format(java.util.Locale.US, "%.8f", state.reservedSats / 100_000_000.0)
            _uiState.value = _uiState.value.copy(
                amountError = "Insufficient available funds. $reservedBtc BTC is reserved in pending transactions."
            )
            hasError = true
        } else if (state.totalSats > state.balanceSats) {
            _uiState.value = _uiState.value.copy(amountError = "Insufficient balance")
            hasError = true
        }

        return !hasError
    }

    /*
     * Validates a Bitcoin address against the wallet network.
     * Returns null if valid, or a user-facing error message.
     *
     * BIP-173 requires bech32 to be all-lowercase or all-uppercase;
     * Trezor rejects mixed case, so we also reject it here for a clearer error.
     */
    private fun validateBitcoinAddress(address: String, walletNetwork: String): String? {
        val mainnet = walletNetwork == "mainnet"

        // Bech32/Bech32m — mandatory lowercase, checked by BIP-173.
        if (address.startsWith("bc1", ignoreCase = true) || address.startsWith("tb1", ignoreCase = true)) {
            val hasUpper = address.any { it.isUpperCase() }
            val hasLower = address.any { it.isLowerCase() }
            if (hasUpper && hasLower) return "Bech32 address must not mix upper and lower case"
            val normalized = address.lowercase()
            if (normalized.length !in 42..62) return "Invalid Bitcoin address"
            if (!normalized.all { it.isLetterOrDigit() }) return "Invalid Bitcoin address"
            val isMainnetAddr = normalized.startsWith("bc1")
            if (mainnet && !isMainnetAddr) return "This is a testnet address, wallet is on mainnet"
            if (!mainnet && isMainnetAddr) return "This is a mainnet address, wallet is on testnet"
            return null
        }

        // Legacy P2PKH/P2SH — base58, case-sensitive.
        val firstChar = address.firstOrNull() ?: return "Invalid Bitcoin address"
        val isMainnetLegacy = firstChar == '1' || firstChar == '3'
        val isTestnetLegacy = firstChar == 'm' || firstChar == 'n' || firstChar == '2'
        if (!isMainnetLegacy && !isTestnetLegacy) return "Invalid Bitcoin address"
        if (address.length !in 25..35) return "Invalid Bitcoin address"
        if (!address.all { it.isLetterOrDigit() }) return "Invalid Bitcoin address"
        if (mainnet && !isMainnetLegacy) return "This is a testnet address, wallet is on mainnet"
        if (!mainnet && !isTestnetLegacy) return "This is a mainnet address, wallet is on testnet"
        return null
    }
}
