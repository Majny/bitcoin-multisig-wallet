package com.example.bitcoinwallet.feature.wallet.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bitcoinwallet.core.api.FeeEstimatesDto
import com.example.bitcoinwallet.core.api.WalletApi
import com.example.bitcoinwallet.core.session.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "SendTransactionVM"

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

    // Loaded data
    val balanceSats: Long = 0L,
    val feeEstimates: FeeEstimatesDto? = null,

    // Computed summary
    val amountSats: Long = 0L,
    val feeSats: Long = 0L,
    val totalSats: Long = 0L,
    val remainingSats: Long = 0L,

    // State flags
    val isLoading: Boolean = true,
    val isSending: Boolean = false,
    val error: String? = null,
    val txCreatedId: String? = null,

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
     * Create the PSBT transaction on the backend.
     */
    fun createTransaction(onSuccess: (String) -> Unit) {
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
                val feeRate = getSelectedFeeRate().toDouble()

                Log.d(TAG, "Creating PSBT: to=${state.recipientAddress}, amount=${state.amountSats} sats, feeRate=$feeRate sat/vB")

                // Use the new PSBT endpoint via WalletApiClient
                val response = WalletApi.client.createPsbt(
                    accessToken = accessToken,
                    walletId = walletId,
                    destinationAddress = state.recipientAddress,
                    amountSats = state.amountSats,
                    feeRate = feeRate
                )

                Log.d(TAG, "PSBT created: id=${response.id}, fee=${response.estimatedFee}")

                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    txCreatedId = response.id
                )
                onSuccess(response.id)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create PSBT", e)
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    error = e.message ?: "Failed to create transaction"
                )
            }
        }
    }

    // ========== Internal ==========

    private fun recalculate() {
        val state = _uiState.value
        val btcAmount = state.amountBtc.toDoubleOrNull() ?: 0.0
        val amountSats = (btcAmount * 100_000_000).toLong()

        // Estimate fee: ~140 vbytes for a simple 1-in 2-out segwit tx
        val estimatedVsize = 140
        val feeRate = getSelectedFeeRate()
        val feeSats = (estimatedVsize * feeRate).toLong()

        val totalSats = amountSats + feeSats
        val remainingSats = state.balanceSats - totalSats

        _uiState.value = state.copy(
            amountSats = amountSats,
            feeSats = feeSats,
            totalSats = totalSats,
            remainingSats = maxOf(remainingSats, 0L)
        )
    }

    private fun getSelectedFeeRate(): Int {
        val state = _uiState.value
        // In manual mode, use custom fee rate
        if (!state.autoSelect) {
            return state.customFeeRate.toIntOrNull() ?: 1
        }
        // In auto mode, use preset priorities
        val fees = state.feeEstimates ?: return 5 // default fallback
        return when (state.feePriority) {
            FeePriority.LOW -> fees.hourFee
            FeePriority.MEDIUM -> fees.halfHourFee
            FeePriority.HIGH -> fees.fastestFee
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
        } else if (state.totalSats > state.balanceSats) {
            _uiState.value = _uiState.value.copy(amountError = "Insufficient balance")
            hasError = true
        } else if (state.amountSats < 546) {
            _uiState.value = _uiState.value.copy(amountError = "Amount below dust limit (546 sats)")
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
