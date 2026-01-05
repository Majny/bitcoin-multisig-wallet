package com.example.bitcoinwallet.core.session

import com.example.bitcoinwallet.core.signer.TrezorDeviceIdentity
import com.example.bitcoinwallet.core.signer.UserSession

object SessionStore {
    @Volatile var pendingIdentity: TrezorDeviceIdentity? = null
    @Volatile var session: UserSession? = null

    @Volatile var activeWalletId: String? = null
    @Volatile var walletsFetchedAtMs: Long? = null

    fun clearAuth() {
        pendingIdentity = null
        session = null
        activeWalletId = null
        walletsFetchedAtMs = null
    }

    fun hasWalletSelected(): Boolean = !activeWalletId.isNullOrBlank()
}
