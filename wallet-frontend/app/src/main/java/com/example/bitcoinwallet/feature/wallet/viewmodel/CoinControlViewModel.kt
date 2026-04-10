package com.example.bitcoinwallet.feature.wallet.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bitcoinwallet.core.api.WalletApi
import com.example.bitcoinwallet.core.api.WalletUtxoDto
import com.example.bitcoinwallet.core.session.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "CoinControlVM"

/**
 * Sort order for UTXO list.
 */
enum class UtxoSortOrder(val label: String) {
    AMOUNT("Amount"),
    STATUS("Confirmation Status"),
    ADDRESS("Address")
}

/**
 * A UTXO item with selection state for coin control.
 */
data class SelectableUtxo(
    val txid: String,
    val vout: Int,
    val valueSats: Long,
    val address: String,
    val addressType: String,
    val confirmed: Boolean,
    val selected: Boolean = false,
    val reserved: Boolean = false
) {
    val key: String get() = "$txid:$vout"
    val valueBtc: Double get() = valueSats / 100_000_000.0

    fun formatBtc(): String = String.format(java.util.Locale.US, "%.8f BTC", valueBtc)

    fun shortAddress(): String =
        if (address.length > 16) "${address.take(12)}..." else address
}

/**
 * UI State for the Coin Control screen.
 */
data class CoinControlUiState(
    val utxos: List<SelectableUtxo> = emptyList(),
    val sortOrder: UtxoSortOrder = UtxoSortOrder.AMOUNT,
    val isLoading: Boolean = true,
    val error: String? = null,
    val showSortMenu: Boolean = false
) {
    val selectedCount: Int get() = utxos.count { it.selected }
    val selectedSats: Long get() = utxos.filter { it.selected }.sumOf { it.valueSats }
    val selectedBtc: Double get() = selectedSats / 100_000_000.0

    fun formatSelectedBtc(): String =
        if (selectedSats == 0L) "0 BTC"
        else String.format(java.util.Locale.US, "%.8f BTC", selectedBtc)
}

/**
 * ViewModel for the Coin Control (UTXO selection) screen.
 */
class CoinControlViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CoinControlUiState())
    val uiState: StateFlow<CoinControlUiState> = _uiState.asStateFlow()

    private val repository = WalletApi.repository

    // Pre-selected UTXOs (passed from SendTransactionViewModel)
    private var preSelectedKeys: Set<String> = emptySet()

    init {
        loadUtxos()
    }

    fun setPreSelected(keys: Set<String>) {
        preSelectedKeys = keys
        // Re-apply if UTXOs already loaded
        if (_uiState.value.utxos.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(
                utxos = _uiState.value.utxos.map {
                    it.copy(selected = it.key in preSelectedKeys)
                }
            )
        }
    }

    private fun loadUtxos() {
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
                val response = repository.getWalletUtxos(walletId, accessToken)
                Log.d(TAG, "Loaded ${response.utxos.size} UTXOs, total: ${response.totalSats} sats")

                // Zjisti které UTXOs jsou rezervované v pending/signed PSBTs
                val reservedKeys = try {
                    val psbts = WalletApi.client.listPsbtsForWallet(walletId, accessToken)
                    psbts.psbts
                        .filter { it.status == "pending" || it.status == "signed" }
                        .flatMap { psbt ->
                            psbt.trezorConnectParams?.inputs?.map { "${it.prev_hash}:${it.prev_index}" }
                                ?: emptyList()
                        }
                        .toSet()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load reserved UTXOs", e)
                    emptySet()
                }
                if (reservedKeys.isNotEmpty()) {
                    Log.d(TAG, "Reserved UTXOs (in pending PSBTs): $reservedKeys")
                }

                val items = response.utxos.map { dto ->
                    val key = "${dto.txid}:${dto.vout}"
                    SelectableUtxo(
                        txid = dto.txid,
                        vout = dto.vout,
                        valueSats = dto.valueSats,
                        address = dto.address,
                        addressType = dto.addressType,
                        confirmed = dto.confirmed,
                        selected = key in preSelectedKeys,
                        reserved = key in reservedKeys
                    )
                }

                _uiState.value = _uiState.value.copy(
                    utxos = sortUtxos(items, _uiState.value.sortOrder),
                    isLoading = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load UTXOs", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load UTXOs"
                )
            }
        }
    }

    // ========== User Actions ==========

    fun toggleUtxo(key: String) {
        _uiState.value = _uiState.value.copy(
            utxos = _uiState.value.utxos.map {
                if (it.key == key && !it.reserved) it.copy(selected = !it.selected) else it
            }
        )
    }

    fun toggleSortMenu() {
        _uiState.value = _uiState.value.copy(showSortMenu = !_uiState.value.showSortMenu)
    }

    fun onSortOrderChanged(order: UtxoSortOrder) {
        _uiState.value = _uiState.value.copy(
            sortOrder = order,
            utxos = sortUtxos(_uiState.value.utxos, order),
            showSortMenu = false
        )
    }

    /**
     * Returns the list of selected UTXO keys (txid:vout).
     */
    fun getSelectedUtxos(): List<SelectableUtxo> =
        _uiState.value.utxos.filter { it.selected }

    // ========== Internal ==========

    private fun sortUtxos(utxos: List<SelectableUtxo>, order: UtxoSortOrder): List<SelectableUtxo> =
        when (order) {
            UtxoSortOrder.AMOUNT -> utxos.sortedByDescending { it.valueSats }
            UtxoSortOrder.STATUS -> utxos.sortedByDescending { it.confirmed }
            UtxoSortOrder.ADDRESS -> utxos.sortedBy { it.address }
        }
}
