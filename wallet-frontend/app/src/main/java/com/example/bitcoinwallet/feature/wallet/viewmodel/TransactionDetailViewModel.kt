package com.example.bitcoinwallet.feature.wallet.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bitcoinwallet.core.api.WalletApi
import com.example.bitcoinwallet.core.api.TxInputDto
import com.example.bitcoinwallet.core.api.TxOutputDto
import com.example.bitcoinwallet.core.session.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class TransactionDetailUiState(
    val txid: String = "",
    val confirmed: Boolean = false,
    val confirmations: String = "Unconfirmed",
    val blockHeight: Int? = null,
    val timestamp: String = "",
    val feeSats: Long = 0,
    val size: Int = 0,
    val weight: Int = 0,
    val inputs: List<TxInputDto> = emptyList(),
    val outputs: List<TxOutputDto> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
) {
    val feeBtc: String get() = formatSatsToBtc(feeSats)
    val feeRate: String get() = if (weight > 0) {
        val vsize = (weight + 3) / 4
        "%.1f sat/vB".format(feeSats.toDouble() / vsize)
    } else ""

    val totalOutputSats: Long get() = outputs.sumOf { it.valueSats }
}

private fun formatSatsToBtc(sats: Long): String {
    val btc = sats / 100_000_000.0
    return "%.8f BTC".format(btc)
}

class TransactionDetailViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionDetailUiState())
    val uiState: StateFlow<TransactionDetailUiState> = _uiState

    fun loadTransaction(txid: String) {
        if (txid.isBlank()) return
        viewModelScope.launch {
            _uiState.value = TransactionDetailUiState(txid = txid, isLoading = true)
            try {
                val token = SessionStore.session?.accessToken
                    ?: throw IllegalStateException("Not authenticated")
                val walletId = SessionStore.activeWalletId

                val dto = WalletApi.client.getTransactionDetail(txid, token, walletId)

                val timestampText = dto.blockTime?.let { epoch ->
                    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    Instant.ofEpochSecond(epoch)
                        .atZone(ZoneId.systemDefault())
                        .format(formatter)
                } ?: ""

                val confirmText = if (dto.confirmed) "Confirmed" else "Unconfirmed"

                _uiState.value = TransactionDetailUiState(
                    txid = dto.txid,
                    confirmed = dto.confirmed,
                    confirmations = confirmText,
                    blockHeight = dto.blockHeight,
                    timestamp = timestampText,
                    feeSats = dto.fee,
                    size = dto.size,
                    weight = dto.weight,
                    inputs = dto.inputs,
                    outputs = dto.outputs,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load transaction"
                )
            }
        }
    }
}
