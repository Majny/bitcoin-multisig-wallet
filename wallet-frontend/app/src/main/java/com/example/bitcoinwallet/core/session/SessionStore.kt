package com.example.bitcoinwallet.core.session

import com.example.bitcoinwallet.core.signer.TrezorDeviceIdentity
import com.example.bitcoinwallet.core.signer.UserSession

object SessionStore {
    @Volatile var pendingIdentity: TrezorDeviceIdentity? = null
    @Volatile var session: UserSession? = null

    @Volatile var activeWalletId: String? = null
    @Volatile var walletsFetchedAtMs: Long? = null

    /** Signed PSBT base64 returned from Trezor after signing. */
    @Volatile var pendingSignedPsbt: String? = null

    /** Preferred fiat currency for balance display (czk, usd, eur). */
    @Volatile var preferredCurrency: String = "czk"

    fun clearAuth() {
        pendingIdentity = null
        session = null
        activeWalletId = null
        walletsFetchedAtMs = null
        pendingSignedPsbt = null
        preferredCurrency = "czk"
    }

    fun hasWalletSelected(): Boolean = !activeWalletId.isNullOrBlank()
}
