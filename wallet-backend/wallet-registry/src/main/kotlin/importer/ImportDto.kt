package cz.majny.wallet.registry.importer

import cz.majny.wallet.registry.api.WalletDetail
import kotlinx.serialization.Serializable

// Request to import a wallet from an output descriptor.
// Example (2-of-3 multisig):
//   wsh(sortedmulti(2,[aabbccdd/48'/0'/0'/2']xpub6D.../0/STAR,[eeff0011/48'/0'/0'/2']xpub6E.../0/STAR,[11223344/48'/0'/0'/2']xpub6F.../0/STAR))
// A trailing #checksum is stripped. Receive and change descriptors may be
// supplied on separate lines; if only the receive descriptor is given, the
// change one is derived by replacing /0/STAR with /1/STAR.
@Serializable
data class ImportWalletRequest(
    /** Raw output descriptor string (required) */
    val descriptor: String,

    /** Network: "mainnet" or "testnet" */
    val network: String = "mainnet",

    /** Optional label for the wallet */
    val label: String? = null,

    /** Birth block height for rescan optimization (optional) */
    val birthHeight: Int? = null,

    /** Device ID of the importing device (for auto-attach) */
    val deviceId: String? = null,

    /** Fingerprint of the importing device's Trezor (for cosigner matching) */
    val deviceFingerprint: String? = null,

    /** Account index of the active wallet (for disambiguating same-fingerprint cosigners) */
    val accountIndex: Int? = null
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
