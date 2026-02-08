package cz.majny.wallet.registry

import org.bitcoinj.base.BitcoinNetwork
import org.bitcoinj.base.Network
import org.bitcoinj.base.ScriptType
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.slf4j.LoggerFactory

/**
 * Derives Bitcoin addresses from output descriptors using BitcoinJ.
 *
 * Supported descriptor types:
 * - wpkh([fp/84h/0h/0h]xpub.../0/star) -> P2WPKH (Native SegWit, bc1q...)
 * - tr([fp/86h/0h/0h]xpub.../0/star)   -> P2TR (Taproot, bc1p...) -- limited
 *
 * Uses BIP-32 hierarchical deterministic key derivation:
 *   xpub (account level) -> /chain/index -> public key -> address
 *   chain = 0 (receive), 1 (change)
 */
object AddressDerivation {

    private val log = LoggerFactory.getLogger(AddressDerivation::class.java)

    /** Regex to extract xpub/tpub from a descriptor string. */
    private val XPUB_RE = Regex("""([xtX]pub[1-9A-HJ-NP-Za-km-z]{79,120})""")

    /**
     * Default number of receive + change addresses to derive for a new wallet.
     * BIP-44 gap limit is 20; we pre-derive that many.
     */
    const val DEFAULT_GAP_LIMIT = 20

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Derive a batch of addresses from a descriptor.
     *
     * @param descriptor  Output descriptor, e.g. "wpkh([fp/84h/0h/0h]xpub.../0/star)"
     * @param network     "mainnet" or "testnet"
     * @param chain       0 = receive, 1 = change
     * @param fromIndex   first child index (inclusive)
     * @param count       how many addresses to derive
     * @return list of (index, address) pairs
     */
    fun deriveAddresses(
        descriptor: String,
        network: String,
        chain: Int = 0,
        fromIndex: Int = 0,
        count: Int = DEFAULT_GAP_LIMIT
    ): List<DerivedAddress> {
        val btcNetwork = bitcoinNetwork(network)
        val xpub = extractXpub(descriptor)
            ?: throw IllegalArgumentException("No xpub found in descriptor: ${descriptor.take(60)}")
        val scriptType = detectScriptType(descriptor)

        log.info(
            "Deriving {} addresses: script={}, chain={}, range=[{}..{}), network={}",
            count, scriptType, chain, fromIndex, fromIndex + count, network
        )

        val accountKey = DeterministicKey.deserializeB58(xpub, btcNetwork)

        // child key for the chain (0 = receive, 1 = change)
        val chainKey = HDKeyDerivation.deriveChildKey(accountKey, chain)

        return (fromIndex until fromIndex + count).map { idx ->
            val childKey = HDKeyDerivation.deriveChildKey(chainKey, idx)
            val addr = toAddress(childKey, scriptType, btcNetwork)
            DerivedAddress(index = idx, address = addr)
        }
    }

    /**
     * Convenience: derive a single address.
     */
    fun deriveAddress(
        descriptor: String,
        network: String,
        chain: Int = 0,
        index: Int = 0
    ): String =
        deriveAddresses(descriptor, network, chain, index, count = 1)
            .first().address

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun toAddress(
        key: DeterministicKey,
        scriptType: DescriptorType,
        network: Network
    ): String = when (scriptType) {
        DescriptorType.P2WPKH ->
            key.toAddress(ScriptType.P2WPKH, network).toString()

        DescriptorType.P2TR ->
            // BitcoinJ 0.17 has limited Taproot support.
            // Try P2TR first; fall back to P2WPKH derivation with a warning.
            try {
                key.toAddress(ScriptType.P2TR, network).toString()
            } catch (_: Exception) {
                log.warn("P2TR not fully supported by BitcoinJ 0.17 - falling back to P2WPKH address")
                key.toAddress(ScriptType.P2WPKH, network).toString()
            }
    }

    private fun extractXpub(descriptor: String): String? =
        XPUB_RE.find(descriptor)?.value

    private fun detectScriptType(descriptor: String): DescriptorType = when {
        descriptor.startsWith("wpkh(") || descriptor.contains("wpkh(") -> DescriptorType.P2WPKH
        descriptor.startsWith("tr(")   || descriptor.contains("tr(")   -> DescriptorType.P2TR
        descriptor.startsWith("wsh(")  || descriptor.contains("wsh(")  -> DescriptorType.P2WPKH // multisig fallback
        else -> {
            log.warn("Unknown descriptor prefix, defaulting to P2WPKH: {}", descriptor.take(30))
            DescriptorType.P2WPKH
        }
    }

    private fun bitcoinNetwork(network: String): BitcoinNetwork = when (network.lowercase()) {
        "mainnet", "bitcoin" -> BitcoinNetwork.MAINNET
        else -> BitcoinNetwork.TESTNET
    }

    private enum class DescriptorType { P2WPKH, P2TR }

    data class DerivedAddress(val index: Int, val address: String)
}
