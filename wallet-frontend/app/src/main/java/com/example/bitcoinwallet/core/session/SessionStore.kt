package com.example.bitcoinwallet.core.session

import com.example.bitcoinwallet.core.signer.TrezorDeviceIdentity
import com.example.bitcoinwallet.core.signer.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SignResultType { SIGNED_PSBT, SERIALIZED_TX }

object SessionStore {
    @Volatile var pendingIdentity: TrezorDeviceIdentity? = null
    @Volatile var session: UserSession? = null

    @Volatile var activeWalletId: String? = null
    @Volatile var walletsFetchedAtMs: Long? = null

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

    /** Preferred fiat currency for balance display (czk, usd, eur). */
    private val _preferredCurrency = MutableStateFlow("czk")
    val preferredCurrency: StateFlow<String> = _preferredCurrency.asStateFlow()

    fun setPreferredCurrency(currency: String) {
        _preferredCurrency.value = currency
    }

    fun clearAuth() {
        pendingIdentity = null
        session = null
        activeWalletId = null
        walletsFetchedAtMs = null
        pendingBatchXpubs = mutableListOf()
        _pendingSignedPsbt.value = null
        _pendingSignType.value = null
        _preferredCurrency.value = "czk"
    }

    fun hasWalletSelected(): Boolean = !activeWalletId.isNullOrBlank()
}
