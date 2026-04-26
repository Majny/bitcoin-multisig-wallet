/* DTOs for account discovery and on-the-fly address derivation. Used during
 * Trezor connect to figure out which accounts are worth importing. */
package cz.majny.wallet.gateway.dto

import kotlinx.serialization.Serializable

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

