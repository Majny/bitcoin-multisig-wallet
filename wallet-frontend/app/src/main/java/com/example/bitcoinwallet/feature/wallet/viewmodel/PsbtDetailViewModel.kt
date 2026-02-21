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

private const val TAG = "PsbtDetailVM"

/**
 * UI State for the PSBT Detail screen.
 */
data class PsbtDetailUiState(
    val psbtId: String = "",
    val walletId: String = "",
    val status: String = "",
    val requiredSigs: Int = 0,
    val currentSigs: Int = 0,
    val totalOutputSats: Long = 0,
    val estimatedFeeSats: Long = 0,
    val psbtBase64: String = "",
    val label: String? = null,
    val txid: String? = null,
    val signatures: List<SignatureUiInfo> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val broadcastSuccess: Boolean = false
) {
    /** e.g. "2 of 3 required" */
    val signaturesLabel: String
        get() = "$currentSigs of $requiredSigs required"

    val isFullySigned: Boolean
        get() = currentSigs >= requiredSigs

    /** True when the PSBT has been finalized and can be broadcast */
    val canBroadcast: Boolean
        get() = status == "finalized"

    /** True when the PSBT still needs more signatures */
    val canSign: Boolean
        get() = status == "pending" || (status == "signed" && !isFullySigned)

    val isBroadcast: Boolean
        get() = status == "broadcast"

    val transactionAmountBtc: String
        get() = formatSatsToBtc(totalOutputSats)

    val networkFeeBtc: String
        get() = formatSatsToBtc(estimatedFeeSats)

    val totalBtc: String
        get() = formatSatsToBtc(totalOutputSats + estimatedFeeSats)

    private fun formatSatsToBtc(sats: Long): String {
        val btc = sats / 100_000_000.0
        return "%.8f BTC".format(btc)
    }
}

data class SignatureUiInfo(
    val fingerprint: String,
    val deviceId: String,
    val signedAt: String
)

/**
 * ViewModel for the PSBT Detail screen.
 * Loads PSBT details, handles signing and broadcasting.
 */
class PsbtDetailViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PsbtDetailUiState())
    val uiState: StateFlow<PsbtDetailUiState> = _uiState.asStateFlow()

    fun loadPsbt(psbtId: String) {
        _uiState.value = _uiState.value.copy(psbtId = psbtId)
        fetchPsbtDetail(psbtId)
    }

    private fun fetchPsbtDetail(psbtId: String) {
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
                Log.d(TAG, "Loading PSBT detail: $psbtId")
                val dto = WalletApi.client.getPsbtDetail(psbtId, accessToken)
                _uiState.value = mapDtoToState(dto)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading PSBT detail", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load PSBT"
                )
            }
        }
    }

    /**
     * Finalize the PSBT (if fully signed) and then broadcast.
     */
    fun broadcast(onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            val state = _uiState.value
            val accessToken = SessionStore.session?.accessToken ?: return@launch

            _uiState.value = state.copy(isLoading = true, error = null)

            try {
                // If fully signed but not yet finalized, finalize first
                if (state.status != "finalized") {
                    Log.d(TAG, "Finalizing PSBT: ${state.psbtId}")
                    WalletApi.client.finalizePsbt(state.psbtId, accessToken)
                }

                // Broadcast
                Log.d(TAG, "Broadcasting PSBT: ${state.psbtId}")
                val result = WalletApi.client.broadcastPsbt(state.psbtId, accessToken)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    status = "broadcast",
                    txid = result.txid,
                    broadcastSuccess = true
                )
                onSuccess()
            } catch (e: Exception) {
                Log.e(TAG, "Error broadcasting PSBT", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Broadcast failed"
                )
            }
        }
    }

    fun refresh() {
        val psbtId = _uiState.value.psbtId
        if (psbtId.isNotBlank()) {
            fetchPsbtDetail(psbtId)
        }
    }

    private fun mapDtoToState(dto: PsbtDetailDto): PsbtDetailUiState {
        return PsbtDetailUiState(
            psbtId = dto.id,
            walletId = dto.walletId,
            status = dto.status,
            requiredSigs = dto.requiredSigs,
            currentSigs = dto.currentSigs,
            totalOutputSats = dto.totalOutputSats,
            estimatedFeeSats = dto.estimatedFeeSats,
            psbtBase64 = dto.psbtBase64,
            label = dto.label,
            txid = dto.txid,
            signatures = dto.signatures.map {
                SignatureUiInfo(
                    fingerprint = it.fingerprint,
                    deviceId = it.deviceId,
                    signedAt = it.signedAt
                )
            },
            isLoading = false
        )
    }
}
