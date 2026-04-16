package com.example.bitcoinwallet.core.session

import com.example.bitcoinwallet.core.signer.TrezorDeviceIdentity
import com.example.bitcoinwallet.core.signer.UserSession
import com.example.bitcoinwallet.core.signer.UserSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SignResultType { SIGNED_PSBT, SERIALIZED_TX }

/**
 * Identifies which UI flow is awaiting a Trezor signing callback.
 * Prevents a callback meant for one flow (e.g. Send) from being consumed
 * by another flow (e.g. PSBT detail) when both composables observe
 * [SessionStore.pendingSignedPsbt] at the same time.
 */
enum class SignFlow { SEND, PSBT_DETAIL }

object SessionStore {
    @Volatile var pendingIdentity: TrezorDeviceIdentity? = null
    @Volatile var session: UserSession? = null
        set(value) {
            field = value
            // Persist whenever the session changes so it survives process death.
            if (value != null) persistSession(value)
        }

    /** Refresh token is kept in memory alongside the session (never in UserSession DTO). */
    @Volatile var refreshToken: String? = null

    @Volatile var activeWalletId: String? = null
        set(value) {
            field = value
            SessionPersistence.updateActiveWallet(value, activeAccountIndex)
        }
    @Volatile var activeAccountIndex: Int? = null
        set(value) {
            field = value
            SessionPersistence.updateActiveWallet(activeWalletId, value)
        }
    @Volatile var selectedNetwork: String = "testnet"

    /** Account discovery: collected xpubs from Trezor bundle callback. */
    @Volatile var pendingBatchXpubs: MutableList<TrezorDeviceIdentity> = mutableListOf()

    /**
     * Signed PSBT base64 returned from Trezor after signing.
     * StateFlow so that composables automatically re-observe when Trezor returns.
     */
    private val _pendingSignedPsbt = MutableStateFlow<String?>(null)
    val pendingSignedPsbt: StateFlow<String?> = _pendingSignedPsbt.asStateFlow()

    fun setPendingSignedPsbt(value: String?) {
        _pendingSignedPsbt.value = value
    }

    private val _pendingSignType = MutableStateFlow<SignResultType?>(null)
    val pendingSignType: StateFlow<SignResultType?> = _pendingSignType.asStateFlow()

    fun setPendingSignType(value: SignResultType?) {
        _pendingSignType.value = value
    }

    /** Per-input DER signatures from Trezor Connect (for multisig) */
    private val _pendingTrezorSignatures = MutableStateFlow<List<String>?>(null)
    val pendingTrezorSignatures: StateFlow<List<String>?> = _pendingTrezorSignatures.asStateFlow()

    fun setPendingTrezorSignatures(value: List<String>?) {
        _pendingTrezorSignatures.value = value
    }

    /**
     * Which UI flow is currently waiting for a Trezor sign callback.
     * Set by the flow before launching the deeplink, cleared after consuming
     * the result. Composables observing [pendingSignedPsbt] must compare
     * against this before acting on it.
     */
    private val _activeSignFlow = MutableStateFlow<SignFlow?>(null)
    val activeSignFlow: StateFlow<SignFlow?> = _activeSignFlow.asStateFlow()

    fun setActiveSignFlow(flow: SignFlow?) {
        _activeSignFlow.value = flow
    }

    /**
     * Request ID of the Trezor Connect deeplink currently in flight.
     * [TrezorDeeplinkLauncher] generates a random id, stores it here, and
     * embeds the same id in the callback URL. [TrezorCallbackActivity] then
     * compares the returned id against this value and drops callbacks that do
     * not match — guarding against stale, replayed, or spoofed callbacks from
     * other apps.
     */
    @Volatile var pendingRequestId: String? = null

    /** Preferred fiat currency for balance display (czk, usd, eur). */
    private val _preferredCurrency = MutableStateFlow("czk")
    val preferredCurrency: StateFlow<String> = _preferredCurrency.asStateFlow()

    fun setPreferredCurrency(currency: String) {
        _preferredCurrency.value = currency
        SessionPersistence.updatePreferredCurrency(currency)
    }

    fun clearAuth() {
        pendingIdentity = null
        session = null
        refreshToken = null
        activeWalletId = null
        activeAccountIndex = null
        selectedNetwork = "testnet"
        pendingBatchXpubs = mutableListOf()
        _pendingSignedPsbt.value = null
        _pendingSignType.value = null
        _pendingTrezorSignatures.value = null
        _activeSignFlow.value = null
        pendingRequestId = null
        _preferredCurrency.value = "czk"
        SessionPersistence.clear()
    }

    fun hasWalletSelected(): Boolean = !activeWalletId.isNullOrBlank()

    /** Called from the launcher Activity to rehydrate from disk on cold start. */
    fun restoreFromPersistence() {
        val snap = SessionPersistence.load() ?: return
        refreshToken = snap.refreshToken
        activeWalletId = snap.activeWalletId
        activeAccountIndex = snap.activeAccountIndex
        selectedNetwork = snap.selectedNetwork
        _preferredCurrency.value = snap.preferredCurrency
        // Session is restored with an empty wallets list; the first API call
        // will repopulate it (or refresh the token if the access token expired).
        session = UserSession(
            accessToken = snap.accessToken,
            user = UserSummary(
                id = snap.userId,
                displayName = snap.displayName,
                trezorFingerprint = snap.fingerprint,
                wallets = emptyList()
            )
        )
    }

    private fun persistSession(s: UserSession) {
        SessionPersistence.save(
            SessionPersistence.Snapshot(
                accessToken = s.accessToken,
                refreshToken = refreshToken,
                userId = s.user.id,
                displayName = s.user.displayName,
                fingerprint = s.user.trezorFingerprint,
                activeWalletId = activeWalletId,
                activeAccountIndex = activeAccountIndex,
                selectedNetwork = selectedNetwork,
                preferredCurrency = _preferredCurrency.value
            )
        )
    }
}
