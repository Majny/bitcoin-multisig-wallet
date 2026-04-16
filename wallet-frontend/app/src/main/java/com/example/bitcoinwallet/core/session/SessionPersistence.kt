package com.example.bitcoinwallet.core.session

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persists the essential pieces of a user session across process death so the
 * app can resume instead of sending the user back to the Trezor login screen.
 *
 * NOT encrypted — uses a plain SharedPreferences file. That is acceptable for
 * the thesis demo because the tokens are short-lived and re-issued via the
 * refresh flow, but a production build should swap this for EncryptedSharedPreferences
 * (androidx.security:security-crypto) or store in the Android Keystore.
 *
 * What we persist:
 *  - access + refresh tokens (needed to re-authenticate API calls)
 *  - user id / display name / trezor fingerprint (used across screens)
 *  - activeWalletId + activeAccountIndex (so the dashboard opens on the right wallet)
 *  - selectedNetwork and preferredCurrency
 *
 * What we do NOT persist:
 *  - wallet list (re-fetched from backend on restore; the token is enough)
 *  - pendingSignedPsbt / pendingTrezorSignatures (ephemeral, flow-specific)
 *  - pendingRequestId (a stale request after process death must be rejected anyway)
 */
object SessionPersistence {

    private const val TAG = "SessionPersistence"
    private const val PREFS_NAME = "bitcoinwallet_session"

    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_FINGERPRINT = "fingerprint"
    private const val KEY_ACTIVE_WALLET_ID = "active_wallet_id"
    private const val KEY_ACTIVE_ACCOUNT_INDEX = "active_account_index"
    private const val KEY_SELECTED_NETWORK = "selected_network"
    private const val KEY_PREFERRED_CURRENCY = "preferred_currency"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
            }
        }
    }

    data class Snapshot(
        val accessToken: String,
        val refreshToken: String?,
        val userId: String,
        val displayName: String,
        val fingerprint: String,
        val activeWalletId: String?,
        val activeAccountIndex: Int?,
        val selectedNetwork: String,
        val preferredCurrency: String
    )

    /** Returns the persisted session, or null if none / prefs not initialised. */
    fun load(): Snapshot? {
        val p = prefs ?: return null
        val accessToken = p.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val userId = p.getString(KEY_USER_ID, null) ?: return null
        val displayName = p.getString(KEY_DISPLAY_NAME, null) ?: return null
        val fingerprint = p.getString(KEY_FINGERPRINT, null) ?: return null
        return Snapshot(
            accessToken = accessToken,
            refreshToken = p.getString(KEY_REFRESH_TOKEN, null),
            userId = userId,
            displayName = displayName,
            fingerprint = fingerprint,
            activeWalletId = p.getString(KEY_ACTIVE_WALLET_ID, null),
            activeAccountIndex = p.getInt(KEY_ACTIVE_ACCOUNT_INDEX, -1).takeIf { it >= 0 },
            selectedNetwork = p.getString(KEY_SELECTED_NETWORK, "testnet") ?: "testnet",
            preferredCurrency = p.getString(KEY_PREFERRED_CURRENCY, "czk") ?: "czk"
        )
    }

    fun save(snapshot: Snapshot) {
        val p = prefs ?: run {
            Log.w(TAG, "save() called before init() — session will not survive process death")
            return
        }
        p.edit().apply {
            putString(KEY_ACCESS_TOKEN, snapshot.accessToken)
            if (snapshot.refreshToken != null) putString(KEY_REFRESH_TOKEN, snapshot.refreshToken)
            putString(KEY_USER_ID, snapshot.userId)
            putString(KEY_DISPLAY_NAME, snapshot.displayName)
            putString(KEY_FINGERPRINT, snapshot.fingerprint)
            if (snapshot.activeWalletId != null) {
                putString(KEY_ACTIVE_WALLET_ID, snapshot.activeWalletId)
            } else {
                remove(KEY_ACTIVE_WALLET_ID)
            }
            if (snapshot.activeAccountIndex != null) {
                putInt(KEY_ACTIVE_ACCOUNT_INDEX, snapshot.activeAccountIndex)
            } else {
                remove(KEY_ACTIVE_ACCOUNT_INDEX)
            }
            putString(KEY_SELECTED_NETWORK, snapshot.selectedNetwork)
            putString(KEY_PREFERRED_CURRENCY, snapshot.preferredCurrency)
        }.apply()
    }

    /** Persist the new access token (and optional refresh) after a refresh call. */
    fun updateTokens(accessToken: String, refreshToken: String?) {
        val p = prefs ?: return
        p.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            if (refreshToken != null) putString(KEY_REFRESH_TOKEN, refreshToken)
        }.apply()
    }

    fun updateActiveWallet(walletId: String?, accountIndex: Int?) {
        val p = prefs ?: return
        p.edit().apply {
            if (walletId != null) putString(KEY_ACTIVE_WALLET_ID, walletId) else remove(KEY_ACTIVE_WALLET_ID)
            if (accountIndex != null) putInt(KEY_ACTIVE_ACCOUNT_INDEX, accountIndex) else remove(KEY_ACTIVE_ACCOUNT_INDEX)
        }.apply()
    }

    fun updatePreferredCurrency(currency: String) {
        prefs?.edit()?.putString(KEY_PREFERRED_CURRENCY, currency)?.apply()
    }

    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }
}
