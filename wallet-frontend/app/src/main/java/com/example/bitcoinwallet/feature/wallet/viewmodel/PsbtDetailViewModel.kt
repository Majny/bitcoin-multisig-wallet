package com.example.bitcoinwallet.feature.wallet.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bitcoinwallet.core.api.PsbtDetailDto
import com.example.bitcoinwallet.core.api.SignerDetailDto
import com.example.bitcoinwallet.core.api.SignerStatusResponseDto
import com.example.bitcoinwallet.core.api.TrezorConnectParamsDto
import com.example.bitcoinwallet.core.api.WalletApi
import com.example.bitcoinwallet.core.session.SessionStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "PsbtDetailVM"

/*
 * Signing status of a single cosigner.
 */
enum class SignerStatus { SIGNED, PENDING, MISSING }

/*
 * Single cosigner display info.
 */
data class CosignerUiInfo(
    val fingerprint: String,
    val cosignerIndex: Int,
    val originPath: String? = null,
    val xpub: String? = null,
    val status: SignerStatus,
    val deviceId: String? = null,
    val isMe: Boolean = false,
    val label: String? = null
)

/*
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
    val broadcastSuccess: Boolean = false,
    // Set after the user successfully cancels a draft PSBT - UI uses it to
    // navigate back to the PSBT list once.
    val deleteSuccess: Boolean = false,
    // True while waiting for Trezor Suite to return a sign callback.
    val awaitingTrezor: Boolean = false
) {
    /* e.g. "2 of 3 required" */
    val signaturesLabel: String
        get() = "$currentSigs of $requiredSigs required"

    val isFullySigned: Boolean
        get() = currentSigs >= requiredSigs

    /*
     * True when the PSBT can be broadcast.
     *   "signed"    - enough signatures collected; backend finalises on
     *                 broadcast so this counts as ready.
     *   "finalized" - PSBT was explicitly finalised.
     */
    val canBroadcast: Boolean
        get() = (status == "finalized" || (status == "signed" && isFullySigned))

    /* True when the PSBT still needs more signatures. */
    val canSign: Boolean
        get() = status == "pending" || (status == "signed" && !isFullySigned)

    /*
     * True when this device already contributed its signature.
     * Used to hide the Sign button once the user has signed - re-signing with the
     * same key crashes Trezor Suite and cannot add a new signature anyway.
     */
    val currentUserSigned: Boolean
        get() = cosigners.any { it.isMe && it.status == SignerStatus.SIGNED }

    val remainingSigs: Int
        get() = (requiredSigs - currentSigs).coerceAtLeast(0)

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
    val signedAt: String,
    val cosignerIndex: Int = 0
)

/*
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
                    error = "Not signed in"
                )
                return@launch
            }

            try {
                Log.d(TAG, "Loading PSBT detail: $psbtId")
                // Fetch PSBT detail, signers, and wallet list in parallel. Cosigners
                // must be populated before we flip isLoading=false so custom labels
                // never flash absent on re-entry.
                coroutineScope {
                    val detailDeferred = async {
                        WalletApi.client.getPsbtDetail(psbtId, accessToken)
                    }
                    val signersDeferred = async {
                        runCatching {
                            WalletApi.client.getSignerStatus(psbtId, accessToken)
                        }.onFailure { Log.w(TAG, "Failed to load signers", it) }.getOrNull()
                    }
                    val walletsDeferred = async {
                        runCatching {
                            WalletApi.client.listWallets(accessToken)
                        }.onFailure { Log.w(TAG, "Failed to fetch wallet list for isMe resolution", it) }
                            .getOrNull()
                    }

                    val dto = detailDeferred.await()
                    val signersResp = signersDeferred.await()
                    val wallets = walletsDeferred.await()

                    val walletId = signersResp?.walletId ?: dto.walletId
                    // Prefer the active session's account index - for a single
                    // Trezor that imported the same multisig under several
                    // BIP-48 accounts, listWallets returns one row per (device,
                    // account) pair and a plain firstOrNull(walletId) is
                    // non-deterministic. The active account is the source of
                    // truth for "which cosigner am I right now".
                    val activeAccount = SessionStore.activeAccountIndex
                    val myAccountIndex = wallets
                        ?.firstOrNull { row ->
                            (row.walletId.ifBlank { row.id }) == walletId &&
                                (activeAccount == null || row.accountIndex == activeAccount)
                        }
                        ?.accountIndex ?: activeAccount
                    val myFingerprint = SessionStore.session?.user?.trezorFingerprint

                    val cosigners = buildCosignerList(signersResp, dto.status, myAccountIndex, myFingerprint)
                    _uiState.value = mapDtoToState(dto).copy(cosigners = cosigners)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading PSBT detail", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load PSBT"
                )
            }
        }
    }

    /*
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

    /* Called when Trezor callback reports user cancellation. */
    fun onTrezorCancelled() {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            awaitingTrezor = false,
            error = null
        )
    }

    /*
     * User explicitly aborts the wait for Trezor (e.g. Trezor app crashed,
     * lost the device, gave up). Drops every signal so a callback that
     * arrives later cannot be silently processed against this PSBT.
     */
    fun cancelTrezorWait() {
        SessionStore.clearPendingSignRequest()
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            awaitingTrezor = false,
            error = null
        )
    }

    /* Called when Trezor callback reports a non-cancel failure. */
    fun onTrezorFailed(message: String) {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            awaitingTrezor = false,
            error = message
        )
    }

    /* Called by the UI after it has acted on broadcastSuccess (navigated away). */
    fun consumeBroadcastSuccess() {
        if (_uiState.value.broadcastSuccess) {
            _uiState.value = _uiState.value.copy(broadcastSuccess = false)
        }
    }

    /*
     * Cancels the current PSBT - backend deletes the row and releases its
     * UTXO reservations. Callable while the PSBT is pending or partially
     * signed; refuses on already-broadcast PSBTs (audit history). On success
     * the caller's [onSuccess] is invoked to drive navigation.
     */
    fun cancelPsbt(onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            val state = _uiState.value
            val accessToken = SessionStore.session?.accessToken ?: return@launch
            if (state.psbtId.isBlank() || state.isBroadcast) return@launch

            // If a Trezor sign for THIS PSBT is in flight (the UI normally
            // hides Cancel PSBT during awaitingTrezor, but state can be lost
            // across rotation / process death), abort the sign request too.
            // Without this, a callback arriving after the delete would
            // submit signatures against a now-404 PSBT and bubble up as a
            // "Failed to submit signatures" error to the user.
            if (SessionStore.pendingSignPsbtId == state.psbtId) {
                SessionStore.clearPendingSignRequest()
            }

            _uiState.value = state.copy(isLoading = true, error = null)

            try {
                Log.d(TAG, "Cancelling PSBT: ${state.psbtId}")
                WalletApi.client.deletePsbt(state.psbtId, accessToken)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    deleteSuccess = true
                )
                onSuccess()
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling PSBT", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to cancel PSBT"
                )
            }
        }
    }

    /* Called right before launching the Trezor deeplink for signing. */
    fun markAwaitingTrezor() {
        _uiState.value = _uiState.value.copy(awaitingTrezor = true, error = null)
    }

    /*
     * Handle Trezor Connect signing result for multisig from PSBT detail screen.
     * Submits per-input signatures to backend.
     */
    fun onTrezorSigned(serializedTxHex: String) {
        viewModelScope.launch {
            val accessToken = SessionStore.session?.accessToken ?: return@launch
            val psbtId = _uiState.value.psbtId
            val fingerprint = SessionStore.session?.user?.trezorFingerprint ?: "unknown"
            val trezorSigs = SessionStore.pendingTrezorSignatures.value

            _uiState.value = _uiState.value.copy(isLoading = true, error = null, awaitingTrezor = false)

            try {
                if (trezorSigs != null && trezorSigs.isNotEmpty()) {
                    val signerAccountIdx = SessionStore.activeAccountIndex
                    Log.d(TAG, "Submitting ${trezorSigs.size} Trezor signatures, signerAccountIndex=$signerAccountIdx")
                    // cosignerIndex is resolved server-side via signerAccountIndex,
                    // but we send activeAccountIndex as fallback for consistency
                    val result = WalletApi.client.signTrezor(
                        psbtId = psbtId,
                        accessToken = accessToken,
                        signatures = trezorSigs,
                        cosignerIndex = signerAccountIdx ?: 0,
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

    /*
     * Open the signers dialog. Cosigners are loaded eagerly on detail fetch,
     * so this just flips the flag. If they were missed, refetch.
     */
    fun showSignersDialog() {
        _uiState.value = _uiState.value.copy(showSignersDialog = true)
        if (_uiState.value.cosigners.isEmpty()) {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(signersLoading = true)
                loadCosigners()
            }
        }
    }

    /*
     * Defensive re-fetch when the signers dialog opens and cosigners are unexpectedly empty.
     * The happy path populates cosigners eagerly during fetchPsbtDetail.
     */
    private suspend fun loadCosigners() {
        val accessToken = SessionStore.session?.accessToken ?: return
        val psbtId = _uiState.value.psbtId
        if (psbtId.isBlank()) return
        try {
            Log.d(TAG, "Loading signers for PSBT: $psbtId")
            val response = WalletApi.client.getSignerStatus(psbtId, accessToken)
            val myAccountIndex = resolveMyAccountIndex(response.walletId, accessToken)
            val myFingerprint = SessionStore.session?.user?.trezorFingerprint
            val cosigners = buildCosignerList(response, _uiState.value.status, myAccountIndex, myFingerprint)
            _uiState.value = _uiState.value.copy(
                cosigners = cosigners,
                signersLoading = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error loading signers", e)
            _uiState.value = _uiState.value.copy(signersLoading = false)
        }
    }

    private suspend fun resolveMyAccountIndex(walletId: String, accessToken: String): Int? {
        // Same disambiguation as fetchPsbtDetail - restrict the wallets-list
        // lookup to the active account so a multisig with multiple membership
        // rows does not pick a random one.
        val activeAccount = SessionStore.activeAccountIndex
        val fromWallets = try {
            WalletApi.client.listWallets(accessToken)
                .firstOrNull { row ->
                    (row.walletId.ifBlank { row.id }) == walletId &&
                        (activeAccount == null || row.accountIndex == activeAccount)
                }
                ?.accountIndex
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch wallet list for isMe resolution", e)
            null
        }
        return fromWallets ?: activeAccount
    }

    private fun buildCosignerList(
        signersResp: SignerStatusResponseDto?,
        psbtStatus: String,
        myAccountIndex: Int?,
        myFingerprint: String?
    ): List<CosignerUiInfo> {
        val signers = signersResp?.signers ?: return emptyList()
        // Compute "which row is me" once across the whole roster instead of
        // per-row - the right answer depends on what the OTHER cosigners look
        // like (e.g. whether an ambiguous account-only fallback is safe), and
        // a per-row decision can't see that.
        val meIdx = findMyCosignerIndex(signers, myAccountIndex, myFingerprint)
        return signers.mapIndexed { i, signer ->
            val status = when {
                signer.signed -> SignerStatus.SIGNED
                psbtStatus == "pending" || psbtStatus == "signed" -> SignerStatus.PENDING
                else -> SignerStatus.MISSING
            }
            CosignerUiInfo(
                fingerprint = signer.fingerprint,
                cosignerIndex = signer.cosignerIndex,
                originPath = signer.originPath,
                xpub = signer.xpub,
                status = status,
                deviceId = signer.deviceId,
                isMe = i == meIdx,
                label = signer.label
            )
        }
    }

    /*
     * Picks the signer row that represents the active session. Tries strict
     * (fingerprint, accountIndex) match first - that handles N Trezors on
     * the same account, and one Trezor split across multiple BIP-48 accounts.
     * Falls back to account-only when the imported cosigners come from a
     * different Trezor passphrase than the current login (fingerprints all
     * mismatch, but account index is still unique per row). Last fallback is
     * fingerprint alone for older clients with no originPath. Returns -1 if
     * no row matches confidently. normalizeFingerprint folds the decimal
     * uint32 form Trezor Connect returns and the 8-char hex from descriptors
     * into the same shape before comparing.
     */
    private fun findMyCosignerIndex(
        signers: List<SignerDetailDto>,
        myAccountIndex: Int?,
        myFingerprint: String?
    ): Int {
        val myFp = normalizeFingerprint(myFingerprint)
        val accOf: (SignerDetailDto) -> Int? = { signer ->
            signer.originPath?.let { path ->
                val segs = path.replace("'", "").replace("h", "")
                    .split("/").filter { it.isNotBlank() && it != "m" }
                if (segs.size >= 3) segs[2].toIntOrNull() else null
            }
        }
        val fpOf: (SignerDetailDto) -> String? = { signer -> normalizeFingerprint(signer.fingerprint) }

        // Strict (fp, acc) - handles cases 1 and 2.
        if (myFp != null && myAccountIndex != null) {
            val idx = signers.indexOfFirst { fpOf(it) == myFp && accOf(it) == myAccountIndex }
            if (idx >= 0) return idx
        }

        // Account-only - handles case 3 (passphrase wallet mismatch). Only
        // safe if exactly one cosigner sits on this account; otherwise we
        // can't tell which one is "me" without the fingerprint.
        if (myAccountIndex != null) {
            val matches = signers.indices.filter { accOf(signers[it]) == myAccountIndex }
            if (matches.size == 1) return matches[0]
        }

        // Fingerprint-only - handles case 4 (signer has no originPath).
        if (myFp != null) {
            val matches = signers.indices.filter { fpOf(signers[it]) == myFp }
            if (matches.size == 1) return matches[0]
        }

        return -1
    }

    /*
     * Trezor returns master fingerprints as a uint32 decimal string while
     * Bitcoin output descriptors store them as 8-char lowercase hex. Both
     * are the same bytes - we normalise to hex so equality checks line up.
     */
    private fun normalizeFingerprint(fp: String?): String? {
        if (fp.isNullOrBlank()) return null
        val asLong = fp.toLongOrNull()
        return if (asLong != null) "%08x".format(asLong) else fp.lowercase()
    }

    fun dismissSignersDialog() {
        _uiState.value = _uiState.value.copy(showSignersDialog = false)
    }

    fun updateCosignerLabel(cosignerIdx: Int, label: String) {
        val walletId = _uiState.value.walletId.ifBlank {
            SessionStore.activeWalletId ?: return
        }
        viewModelScope.launch {
            val accessToken = SessionStore.session?.accessToken ?: return@launch
            try {
                WalletApi.client.updateCosignerLabel(walletId, cosignerIdx, label, accessToken)
                // Update local state immediately
                val updated = _uiState.value.cosigners.map { c ->
                    if (c.cosignerIndex == cosignerIdx) c.copy(label = label) else c
                }
                _uiState.value = _uiState.value.copy(cosigners = updated)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update cosigner label", e)
            }
        }
    }

    /*
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
                    signedAt = it.signedAt,
                    cosignerIndex = it.cosignerIndex
                )
            },
            // Preserve UI-only state that should survive a data refresh.
            // broadcastSuccess is an *action* signal (user just broadcast) - never
            // derive it from dto.status, otherwise opening an already-broadcast PSBT
            // bounces the user to the "Transaction Sent" screen as if they just sent it.
            showSignersDialog = current.showSignersDialog,
            cosigners = current.cosigners,
            broadcastSuccess = current.broadcastSuccess,
            isLoading = false
        )
    }
}
