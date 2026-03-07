package cz.majny.wallet.gateway.dto

import kotlinx.serialization.Serializable

/**
 * Request to scan multiple derivation paths for activity.
 * Used for account discovery when connecting a Trezor device.
 */
@Serializable
data class ScanAccountsRequest(
    val fingerprint: String,
    val accounts: List<AccountToScan>
)

@Serializable
data class AccountToScan(
    val xpub: String,
    val derivationPath: String
)

/**
 * Response with scan results for each account.
 */
@Serializable
data class ScanAccountsResponse(
    val fingerprint: String,
    val accounts: List<ScannedAccount>
)

@Serializable
data class ScannedAccount(
    val derivationPath: String,
    val xpub: String,
    val hasActivity: Boolean,
    val utxoCount: Int,
    val totalSats: Long,
    val scriptType: String,
    val network: String
)

/**
 * Request to derive addresses from a descriptor.
 * Sent to wallet-registry POST /registry/derive-addresses.
 */
@Serializable
data class DeriveAddressesRequest(
    val descriptor: String,
    val network: String,
    val count: Int = 5
)

@Serializable
data class DeriveAddressesResponse(
    val addresses: List<String>
)

