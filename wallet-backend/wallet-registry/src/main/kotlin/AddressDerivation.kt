package cz.majny.wallet.registry

import org.bitcoinj.base.BitcoinNetwork
import org.bitcoinj.base.Network
import org.bitcoinj.base.ScriptType
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Derives Bitcoin addresses from output descriptors using BitcoinJ.
 *
 * Supported descriptor types:
 * - `wpkh([fp/84h/0h/0h]xpub.../0/star)` -> P2WPKH (Native SegWit, bc1q...)
 * - `tr([fp/86h/0h/0h]xpub.../0/star)` -> P2TR (Taproot, bc1p...) -- limited
 * - `wsh(sortedmulti(M,[fp/48h/0h/0h/2h]xpub.../0/star,...))` -> P2WSH (multisig, bc1q...)
 * - `wsh(multi(M,...))` -> P2WSH (multisig, unsorted)
 *
 * Uses BIP-32 hierarchical deterministic key derivation:
 *   xpub (account level) -> /chain/index -> public key -> address
 *   chain = 0 (receive), 1 (change)
 */
object AddressDerivation {

    private val log = LoggerFactory.getLogger(AddressDerivation::class.java)

    /** Regex to extract xpub/tpub from a descriptor string. */
    private val XPUB_RE = Regex("""([xtX]pub[1-9A-HJ-NP-Za-km-z]{79,120})""")

    /** Regex to extract ALL xpubs from a multisig descriptor. */
    private val ALL_XPUBS_RE = Regex("""([xtX]pub[1-9A-HJ-NP-Za-km-z]{79,120})""")

    /** Regex to extract M from multi(M, ...) or sortedmulti(M, ...) */
    private val MULTI_M_RE = Regex("""(?:sorted)?multi\((\d+)\s*,""")

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
        val scriptType = detectScriptType(descriptor)

        return when (scriptType) {
            DescriptorType.P2WSH_MULTISIG -> deriveMultisigAddresses(
                descriptor, btcNetwork, network, chain, fromIndex, count
            )
            else -> deriveSinglesigAddresses(
                descriptor, btcNetwork, scriptType, chain, fromIndex, count
            )
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
    // Single-sig derivation (P2WPKH, P2TR)
    // ------------------------------------------------------------------

    private fun deriveSinglesigAddresses(
        descriptor: String,
        btcNetwork: BitcoinNetwork,
        scriptType: DescriptorType,
        chain: Int,
        fromIndex: Int,
        count: Int
    ): List<DerivedAddress> {
        val xpub = extractXpub(descriptor)
            ?: throw IllegalArgumentException("No xpub found in descriptor: ${descriptor.take(60)}")

        log.info(
            "Deriving {} singlesig addresses: script={}, chain={}, range=[{}..{})",
            count, scriptType, chain, fromIndex, fromIndex + count
        )

        val accountKey = DeterministicKey.deserializeB58(xpub, btcNetwork)
        val chainKey = HDKeyDerivation.deriveChildKey(accountKey, chain)

        return (fromIndex until fromIndex + count).map { idx ->
            val childKey = HDKeyDerivation.deriveChildKey(chainKey, idx)
            val addr = toAddress(childKey, scriptType, btcNetwork)
            DerivedAddress(index = idx, address = addr)
        }
    }

    // ------------------------------------------------------------------
    // Multisig P2WSH derivation
    // ------------------------------------------------------------------

    /**
     * Derives P2WSH multisig addresses.
     *
     * For each address index:
     * 1. Derive child pubkey from each cosigner's xpub at chain/index
     * 2. Sort pubkeys lexicographically (BIP-67, for sortedmulti)
     * 3. Build witness script: OP_M <pubkey1> <pubkey2> ... <pubkeyN> OP_N OP_CHECKMULTISIG
     * 4. SHA256 hash the witness script → 32-byte witness program
     * 5. Encode as bech32 P2WSH address (bc1q... 62 chars)
     */
    private fun deriveMultisigAddresses(
        descriptor: String,
        btcNetwork: BitcoinNetwork,
        network: String,
        chain: Int,
        fromIndex: Int,
        count: Int
    ): List<DerivedAddress> {
        // Extract M from descriptor
        val mMatch = MULTI_M_RE.find(descriptor)
            ?: throw IllegalArgumentException("Cannot find M in multi(): ${descriptor.take(60)}")
        val m = mMatch.groupValues[1].toInt()
        val isSorted = descriptor.contains("sortedmulti(")

        // Extract all xpubs
        val xpubs = ALL_XPUBS_RE.findAll(descriptor).map { it.value }.toList()
        if (xpubs.isEmpty()) {
            throw IllegalArgumentException("No xpubs found in multisig descriptor")
        }
        val n = xpubs.size

        log.info(
            "Deriving {} P2WSH multisig addresses: {}of{}, sorted={}, chain={}, range=[{}..{})",
            count, m, n, isSorted, chain, fromIndex, fromIndex + count
        )

        // Parse all account-level keys
        val accountKeys = xpubs.map { xpub ->
            DeterministicKey.deserializeB58(xpub, btcNetwork)
        }

        // Derive chain-level keys (once per cosigner)
        val chainKeys = accountKeys.map { key ->
            HDKeyDerivation.deriveChildKey(key, chain)
        }

        val hrp = if (network.lowercase() in listOf("mainnet", "bitcoin")) "bc" else "tb"

        return (fromIndex until fromIndex + count).map { idx ->
            // Derive child pubkey for each cosigner at this index
            val childPubkeys = chainKeys.map { chainKey ->
                val childKey = HDKeyDerivation.deriveChildKey(chainKey, idx)
                childKey.pubKey // compressed 33-byte pubkey
            }

            // BIP-67: sort pubkeys lexicographically
            val orderedPubkeys = if (isSorted) {
                childPubkeys.sortedWith(compareBy<ByteArray> { it.size }.thenBy { it.toHex() })
            } else {
                childPubkeys
            }

            // Build witness script: OP_M <pubkey1> ... <pubkeyN> OP_N OP_CHECKMULTISIG
            val witnessScript = buildMultisigWitnessScript(m, orderedPubkeys)

            // P2WSH: SHA256(witnessScript) → 32-byte witness program
            val sha256 = MessageDigest.getInstance("SHA-256").digest(witnessScript)

            // Bech32 encode: witness version 0 + 32-byte program
            val addr = segwitToBech32(hrp, 0, sha256)

            DerivedAddress(index = idx, address = addr)
        }
    }

    /**
     * Build the multisig witness script bytes:
     *   OP_M <pubkey1_push> <pubkey1> ... <pubkeyN_push> <pubkeyN> OP_N OP_CHECKMULTISIG
     */
    private fun buildMultisigWitnessScript(m: Int, pubkeys: List<ByteArray>): ByteArray {
        val n = pubkeys.size
        val buf = mutableListOf<Byte>()

        // OP_M (OP_1 = 0x51, OP_2 = 0x52, ..., OP_16 = 0x60)
        buf.add((0x50 + m).toByte())

        // Push each pubkey (33 bytes = 0x21 push)
        for (pk in pubkeys) {
            buf.add(pk.size.toByte())
            buf.addAll(pk.toList())
        }

        // OP_N
        buf.add((0x50 + n).toByte())

        // OP_CHECKMULTISIG
        buf.add(0xAE.toByte())

        return buf.toByteArray()
    }

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
            try {
                key.toAddress(ScriptType.P2TR, network).toString()
            } catch (_: Exception) {
                log.warn("P2TR not fully supported by BitcoinJ — falling back to P2WPKH address")
                key.toAddress(ScriptType.P2WPKH, network).toString()
            }

        DescriptorType.P2WSH_MULTISIG ->
            throw IllegalStateException("P2WSH_MULTISIG should use deriveMultisigAddresses()")
    }

    private fun extractXpub(descriptor: String): String? =
        XPUB_RE.find(descriptor)?.value

    private fun detectScriptType(descriptor: String): DescriptorType = when {
        (descriptor.contains("wsh(") && descriptor.contains("multi(")) -> DescriptorType.P2WSH_MULTISIG
        descriptor.startsWith("wpkh(") || descriptor.contains("wpkh(") -> DescriptorType.P2WPKH
        descriptor.startsWith("tr(")   || descriptor.contains("tr(")   -> DescriptorType.P2TR
        else -> {
            log.warn("Unknown descriptor prefix, defaulting to P2WPKH: {}", descriptor.take(30))
            DescriptorType.P2WPKH
        }
    }

    private fun bitcoinNetwork(network: String): BitcoinNetwork = when (network.lowercase()) {
        "mainnet", "bitcoin" -> BitcoinNetwork.MAINNET
        else -> BitcoinNetwork.TESTNET
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------
    // Manual bech32 encoding (BitcoinJ 0.17 has no segwitToBech32 method)
    // ------------------------------------------------------------------

    private val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    private fun segwitToBech32(hrp: String, witnessVersion: Int, program: ByteArray): String {
        // Convert 8-bit witness program to 5-bit groups
        val data = convertBits8to5(program)
        // Prepend witness version
        val payload = intArrayOf(witnessVersion) + data
        // Compute bech32 checksum
        val checksum = createBech32Checksum(hrp, payload)
        val sb = StringBuilder(hrp.length + 1 + payload.size + checksum.size)
        sb.append(hrp).append('1')
        for (v in payload) sb.append(BECH32_CHARSET[v])
        for (v in checksum) sb.append(BECH32_CHARSET[v])
        return sb.toString()
    }

    private fun convertBits8to5(data: ByteArray): IntArray {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Int>()
        for (b in data) {
            acc = (acc shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                result.add((acc shr bits) and 31)
            }
        }
        if (bits > 0) {
            result.add((acc shl (5 - bits)) and 31)
        }
        return result.toIntArray()
    }

    private fun bech32Polymod(values: IntArray): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val top = chk shr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in gen.indices) {
                if ((top shr i) and 1 == 1) chk = chk xor gen[i]
            }
        }
        return chk
    }

    private fun bech32HrpExpand(hrp: String): IntArray {
        val result = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) result[i] = hrp[i].code shr 5
        // result[hrp.length] = 0 (separator)
        for (i in hrp.indices) result[hrp.length + 1 + i] = hrp[i].code and 31
        return result
    }

    private fun createBech32Checksum(hrp: String, data: IntArray): IntArray {
        val values = bech32HrpExpand(hrp) + data + intArrayOf(0, 0, 0, 0, 0, 0)
        val polymod = bech32Polymod(values) xor 1
        return IntArray(6) { (polymod shr (5 * (5 - it))) and 31 }
    }

    private enum class DescriptorType { P2WPKH, P2TR, P2WSH_MULTISIG }

    data class DerivedAddress(val index: Int, val address: String)
}
