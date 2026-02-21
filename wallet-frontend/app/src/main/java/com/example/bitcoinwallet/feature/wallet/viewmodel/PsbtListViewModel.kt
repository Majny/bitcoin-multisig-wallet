package com.example.bitcoinwallet.feature.wallet.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bitcoinwallet.core.api.PsbtDetailDto
import com.example.bitcoinwallet.core.api.WalletApi
import com.example.bitcoinwallet.core.session.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val TAG = "PsbtListVM"

/**
 * Single PSBT item for the list display.
 */
data class PsbtListItem(
    val id: String,
    val status: String,
    val requiredSigs: Int,
    val currentSigs: Int,
    val totalOutputSats: Long,
    val createdAt: String,
    val label: String?
) {
    /** e.g. "Waiting for 2 signatures" or "Ready to broadcast" or "Broadcast" */
    val statusLabel: String
        get() {
            val remaining = requiredSigs - currentSigs
            return when {
                status == "broadcast" -> "Broadcast"
                status == "finalized" -> "Ready to broadcast"
                remaining <= 0 -> "Fully signed"
                else -> "Waiting for $remaining signature${if (remaining > 1) "s" else ""}"
            }
        }

    /** e.g. "-0.25 BTC" */
    val amountBtcFormatted: String
        get() {
            val btc = totalOutputSats / 100_000_000.0
            return "-%.2f BTC".format(btc)
        }

    /** Formatted date, e.g. "May 27" */
    val dateFormatted: String
        get() = try {
            val odt = OffsetDateTime.parse(createdAt)
            odt.format(DateTimeFormatter.ofPattern("MMM dd", Locale.ENGLISH))
        } catch (_: Exception) {
            createdAt.take(10)
        }
}

data class PsbtListUiState(
    val walletId: String = "",
    val psbts: List<PsbtListItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

/**
 * ViewModel for the PSBT list screen (per multisig wallet).
 */
class PsbtListViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PsbtListUiState())
    val uiState: StateFlow<PsbtListUiState> = _uiState.asStateFlow()

    fun loadPsbts(walletId: String) {
        _uiState.value = _uiState.value.copy(walletId = walletId)
        fetchPsbts(walletId)
    }

    private fun fetchPsbts(walletId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val accessToken = SessionStore.session?.accessToken
            if (accessToken == null) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Není přihlášen uživatel"
                )
                return@launch
            }

            try {
                Log.d(TAG, "Loading PSBTs for wallet: $walletId")
                val response = WalletApi.client.listPsbtsForWallet(walletId, accessToken)

                val items = response.psbts.map { dto ->
                    PsbtListItem(
                        id = dto.id,
                        status = dto.status,
                        requiredSigs = dto.requiredSigs,
                        currentSigs = dto.currentSigs,
                        totalOutputSats = dto.totalOutputSats,
                        createdAt = dto.createdAt,
                        label = dto.label
                    )
                }

                Log.d(TAG, "Loaded ${items.size} PSBTs")

                _uiState.value = _uiState.value.copy(
                    psbts = items,
                    isLoading = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error loading PSBTs", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load PSBTs"
                )
            }
        }
    }

    fun refresh() {
        val walletId = _uiState.value.walletId
        if (walletId.isNotBlank()) {
            fetchPsbts(walletId)
        }
    }
}
