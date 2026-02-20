package cz.majny.wallet.registry.importer

import cz.majny.wallet.registry.api.WalletDetail
import kotlinx.serialization.Serializable

//
// Request to import a wallet from an output descriptor.
//
// Example descriptor (2-of-3 multisig):
//   wsh(sortedmulti(2,[aabbccdd/48'/0'/0'/2']xpub6D.../0/STAR,[eeff0011/48'/0'/0'/2']xpub6E.../0/STAR,[11223344/48'/0'/0'/2']xpub6F.../0/STAR))
//
// The descriptor can include a #checksum suffix which will be stripped.
// You can provide both receive and change descriptors separated by a newline,
// or just the receive descriptor and the change one will be auto-generated (/0/STAR -> /1/STAR).
//
@Serializable
data class ImportWalletRequest(
    /** Raw output descriptor string (required) */
    val descriptor: String,

    /** Network: "mainnet" or "testnet" (default: "mainnet") */
    val network: String = "mainnet",

    /** Optional label for the wallet */
    val label: String? = null,

    /** Birth block height for rescan optimization (optional) */
    val birthHeight: Int? = null,

    /** Device ID of the importing device (for auto-attach) */
    val deviceId: String? = null,

    /** Fingerprint of the importing device's Trezor (for cosigner matching) */
    val deviceFingerprint: String? = null
)

/**
 * Result of a wallet import operation.
 */
@Serializable
data class ImportResult(
    val success: Boolean,
    val walletId: String? = null,
    val isNew: Boolean = false,
    val error: String? = null,
    val wallet: WalletDetail? = null
)
