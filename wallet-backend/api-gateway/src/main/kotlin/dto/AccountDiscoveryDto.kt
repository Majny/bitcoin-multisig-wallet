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
 */
@Serializable
data class DeriveAddressesRequest(
    val descriptor: String,
    val range: List<Int> = listOf(0, 19)  // first 20 addresses by default
)

@Serializable
data class DeriveAddressesResponse(
    val addresses: List<String>
)

/**
 * Result from Bitcoin Core's scantxoutset RPC.
 */
@Serializable
data class ScanTxOutSetResult(
    val success: Boolean,
    val txouts: Int = 0,
    val total_amount: Double = 0.0,
    val unspents: List<UnspentOutput> = emptyList()
)

@Serializable
data class UnspentOutput(
    val txid: String,
    val vout: Int,
    val scriptPubKey: String,
    val desc: String,
    val amount: Double,
    val height: Int
)
