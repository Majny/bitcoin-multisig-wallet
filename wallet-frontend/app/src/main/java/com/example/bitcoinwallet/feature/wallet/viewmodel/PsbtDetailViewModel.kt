package com.example.bitcoinwallet.feature.wallet.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bitcoinwallet.core.api.PsbtDetailDto
import com.example.bitcoinwallet.core.api.TrezorConnectParamsDto
import com.example.bitcoinwallet.core.api.WalletApi
import com.example.bitcoinwallet.core.session.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "PsbtDetailVM"

/**
 * Signing status of a single cosigner.
 */
enum class SignerStatus { SIGNED, PENDING, MISSING }

/**
 * Single cosigner display info.
 */
data class CosignerUiInfo(
    val fingerprint: String,
    val cosignerIndex: Int,
    val originPath: String? = null,
    val xpub: String? = null,
    val status: SignerStatus,
    val deviceId: String? = null,
    val isMe: Boolean = false
)

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
    val trezorConnectParams: TrezorConnectParamsDto? = null,
    val label: String? = null,
    val txid: String? = null,
    val signatures: List<SignatureUiInfo> = emptyList(),
    val cosigners: List<CosignerUiInfo> = emptyList(),
    val showSignersDialog: Boolean = false,
    val signersLoading: Boolean = false,
    val serializedTx: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val broadcastSuccess: Boolean = false
) {
    /** e.g. "2 of 3 required" */
    val signaturesLabel: String
        get() = "$currentSigs of $requiredSigs required"

    val isFullySigned: Boolean
        get() = currentSigs >= requiredSigs

    /**
     * True when the PSBT can be broadcast.
     * "signed" = dost podpisů sesbíráno (backend finalizuje automaticky před broadcastem).
     * "finalized" = PSBT byl explicitně finalizován.
     */
    val canBroadcast: Boolean
        get() = (status == "finalized" || (status == "signed" && isFullySigned))

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
     * Broadcast the fully-signed transaction using the raw serializedTx from Trezor Connect.
     */
    fun broadcast(onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            val state = _uiState.value
            val accessToken = SessionStore.session?.accessToken ?: return@launch

            _uiState.value = state.copy(isLoading = true, error = null)

            try {
                val serializedTx = state.serializedTx
                if (serializedTx == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "No signed transaction available for broadcast"
                    )
                    return@launch
                }

                Log.d(TAG, "Broadcasting via serializedTx (Trezor Connect): ${state.psbtId}")
                val result = WalletApi.client.broadcastRawTx(
                    psbtId = state.psbtId,
                    accessToken = accessToken,
                    txHex = serializedTx
                )
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

    /**
     * Handle Trezor Connect signing result for multisig from PSBT detail screen.
     * Submits per-input signatures to backend.
     */
    fun onTrezorSigned(serializedTxHex: String) {
        viewModelScope.launch {
            val accessToken = SessionStore.session?.accessToken ?: return@launch
            val psbtId = _uiState.value.psbtId
            val fingerprint = SessionStore.session?.user?.trezorFingerprint ?: "unknown"
            val trezorSigs = SessionStore.pendingTrezorSignatures.value

            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                if (trezorSigs != null && trezorSigs.isNotEmpty()) {
                    val signerAccountIdx = SessionStore.activeAccountIndex
                    Log.d(TAG, "Submitting ${trezorSigs.size} Trezor signatures, signerAccountIndex=$signerAccountIdx")
                    val result = WalletApi.client.signTrezor(
                        psbtId = psbtId,
                        accessToken = accessToken,
                        signatures = trezorSigs,
                        cosignerIndex = 0,
                        fingerprint = fingerprint,
                        serializedTx = serializedTxHex,
                        signerAccountIndex = signerAccountIdx
                    )
                    SessionStore.setPendingTrezorSignatures(null)
                    Log.d(TAG, "Signatures submitted: ${result.currentSigs}/${result.requiredSigs}")
                }
                // Refresh to show updated state
                fetchPsbtDetail(psbtId)
            } catch (e: Exception) {
                Log.e(TAG, "Error submitting Trezor signatures", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to submit signatures"
                )
            }
        }
    }

    /**
     * Open the signers dialog and load cosigner status from backend.
     */
    fun showSignersDialog() {
        _uiState.value = _uiState.value.copy(showSignersDialog = true, signersLoading = true)
        viewModelScope.launch {
            val accessToken = SessionStore.session?.accessToken ?: return@launch
            val psbtId = _uiState.value.psbtId

            try {
                Log.d(TAG, "Loading signers for PSBT: $psbtId")
                val response = WalletApi.client.getSignerStatus(psbtId, accessToken)

                val myAccountIndex = SessionStore.activeAccountIndex

                val cosigners = response.signers.map { signer ->
                    val status = when {
                        signer.signed -> SignerStatus.SIGNED
                        // If PSBT is pending and this cosigner hasn't signed → PENDING
                        _uiState.value.status == "pending" || _uiState.value.status == "signed" ->
                            SignerStatus.PENDING
                        else -> SignerStatus.MISSING
                    }
                    CosignerUiInfo(
                        fingerprint = signer.fingerprint,
                        cosignerIndex = signer.cosignerIndex,
                        originPath = signer.originPath,
                        xpub = signer.xpub,
                        status = status,
                        deviceId = signer.deviceId,
                        isMe = signer.originPath?.let { path ->
                            val segments = path.replace("'", "").replace("h", "").split("/")
                            val cosAccount = if (segments.size >= 3) segments[2].toIntOrNull() else null
                            cosAccount == myAccountIndex
                        } ?: false
                    )
                }

                _uiState.value = _uiState.value.copy(
                    cosigners = cosigners,
                    signersLoading = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error loading signers", e)
                _uiState.value = _uiState.value.copy(signersLoading = false)
            }
        }
    }

    fun dismissSignersDialog() {
        _uiState.value = _uiState.value.copy(showSignersDialog = false)
    }

    /**
     * Adjust TrezorConnectParams address_n for the current signer's BIP-48 account.
     * Stored params may have a different signer's account in address_n[2].
     * BIP-48 path: [purpose', coinType', account', scriptType', chain, index]
     */
    private fun adjustTrezorParamsForSigner(
        params: TrezorConnectParamsDto?
    ): TrezorConnectParamsDto? {
        val accountIndex = SessionStore.activeAccountIndex ?: return params
        params ?: return null
        val hardenedAccount = accountIndex.toLong() or 0x80000000L

        fun adjustAddressN(addressN: List<Long>): List<Long> {
            if (addressN.size < 4) return addressN
            // address_n = [purpose', coinType', account', scriptType', chain, index]
            return addressN.toMutableList().also { it[2] = hardenedAccount }
        }

        val adjustedInputs = params.inputs.map { input ->
            input.copy(address_n = adjustAddressN(input.address_n))
        }
        val adjustedOutputs = params.outputs.map { output ->
            val adjN = output.address_n?.let { adjustAddressN(it) }
            output.copy(address_n = adjN)
        }
        Log.d(TAG, "Adjusted TrezorConnectParams for accountIndex=$accountIndex")
        return params.copy(inputs = adjustedInputs, outputs = adjustedOutputs)
    }

    private fun mapDtoToState(dto: PsbtDetailDto): PsbtDetailUiState {
        val current = _uiState.value
        return PsbtDetailUiState(
            psbtId = dto.id,
            walletId = dto.walletId,
            status = dto.status,
            requiredSigs = dto.requiredSigs,
            currentSigs = dto.currentSigs,
            totalOutputSats = dto.totalOutputSats,
            estimatedFeeSats = dto.estimatedFeeSats,
            psbtBase64 = dto.psbtBase64,
            trezorConnectParams = adjustTrezorParamsForSigner(dto.trezorConnectParams),
            serializedTx = dto.serializedTx,
            label = dto.label,
            txid = dto.txid,
            signatures = dto.signatures.map {
                SignatureUiInfo(
                    fingerprint = it.fingerprint,
                    deviceId = it.deviceId,
                    signedAt = it.signedAt
                )
            },
            // Preserve UI-only state that should survive a data refresh
            showSignersDialog = current.showSignersDialog,
            cosigners = if (current.showSignersDialog) current.cosigners else emptyList(),
            broadcastSuccess = current.broadcastSuccess || dto.status == "broadcast",
            isLoading = false
        )
    }
}
