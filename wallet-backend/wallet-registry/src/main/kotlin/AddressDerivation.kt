package cz.majny.wallet.registry

import org.bitcoinj.base.BitcoinNetwork
import org.bitcoinj.base.Network
import org.bitcoinj.base.ScriptType
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.ECKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.slf4j.LoggerFactory
import java.security.MessageDigest

object AddressDerivation {

    private val log = LoggerFactory.getLogger(AddressDerivation::class.java)

    private val XPUB_RE = Regex("""([xtX]pub[1-9A-HJ-NP-Za-km-z]{79,120})""")
    private val MULTI_M_RE = Regex("""(?:sorted)?multi\((\d+)\s*,""")
    // TODO: if we run out of 20, generate more
    const val DEFAULT_GAP_LIMIT = 20

    /*
     * Derives a batch of Bitcoin addresses from a descriptor string.
     * Entry point for all address derivation in wallet-registry.
     * Called by Repository when creating a wallet (20 receive + 20 change)
     * and by Routes POST /registry/derive-addresses for account discovery.
     * Detects singlesig vs multisig from the descriptor and delegates accordingly.
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

    /*
     * Derives singlesig (one-key) P2WPKH/P2TR addresses.
     * Uses BitcoinJ BIP-32 derivation: xpub -> chain key (0=receive, 1=change) -> child key -> address.
     * Returns a list of (index, address) pairs for the requested range.
     */
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

    /*
     * Derives P2WSH multisig addresses from a descriptor like:
     *   wsh(sortedmulti(2,[fp1/48'/1'/0'/2']tpub1.../0/wildcard,[fp2/...]tpub2.../0/wildcard,...))
     *
     * For each address index:
     * 1. Derive child pubkey from each cosigner's xpub at chain/index
     * 2. BIP-67 sort pubkeys lexicographically (so all devices get the same order)
     * 3. Build witness script: OP_M <pubkey1> ... <pubkeyN> OP_N OP_CHECKMULTISIG
     * 4. SHA256 hash the witness script -> 32-byte witness program
     * 5. Bech32 encode -> tb1q.../bc1q... address (62 chars)
     */
    private fun deriveMultisigAddresses(
        descriptor: String,
        btcNetwork: BitcoinNetwork,
        network: String,
        chain: Int,
        fromIndex: Int,
        count: Int
    ): List<DerivedAddress> {
        val mMatch = MULTI_M_RE.find(descriptor)
            ?: throw IllegalArgumentException("Cannot find M in multi(): ${descriptor.take(60)}")
        val m = mMatch.groupValues[1].toInt()
        val isSorted = descriptor.contains("sortedmulti(")

        val xpubs = XPUB_RE.findAll(descriptor).map { it.value }.toList()
        if (xpubs.isEmpty()) {
            throw IllegalArgumentException("No xpubs found in multisig descriptor")
        }
        val n = xpubs.size

        log.info(
            "Deriving {} P2WSH multisig addresses: {}of{}, sorted={}, chain={}, range=[{}..{})",
            count, m, n, isSorted, chain, fromIndex, fromIndex + count
        )

        val accountKeys = xpubs.map { xpub ->
            DeterministicKey.deserializeB58(xpub, btcNetwork)
        }

        // Derive chain-level keys once per cosigner (not per index)
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

            // BIP-67: sort pubkeys so all devices derive the same address
            val orderedPubkeys = if (isSorted) {
                childPubkeys.sortedWith(compareBy<ByteArray> { it.size }.thenBy { it.toHex() })
            } else {
                childPubkeys
            }

            // Build witness script: OP_M <pk1> ... <pkN> OP_N OP_CHECKMULTISIG
            val witnessScript = buildMultisigWitnessScript(m, orderedPubkeys)

            // P2WSH = SHA256(witnessScript) encoded as bech32
            val sha256 = MessageDigest.getInstance("SHA-256").digest(witnessScript)
            val addr = segwitToBech32(hrp, 0, sha256)

            DerivedAddress(index = idx, address = addr)
        }
    }

    /*
     * Builds the raw multisig witness script (Bitcoin Script bytecode).
     * Format: OP_M <push><pubkey1> ... <push><pubkeyN> OP_N OP_CHECKMULTISIG
     * This bytecode defines the spending rules for the multisig address.
     */
    private fun buildMultisigWitnessScript(m: Int, pubkeys: List<ByteArray>): ByteArray {
        val n = pubkeys.size
        val buf = mutableListOf<Byte>()

        buf.add((0x50 + m).toByte()) // OP_M (OP_1=0x51, OP_2=0x52, ...)

        for (pk in pubkeys) {
            buf.add(pk.size.toByte()) // push 33 bytes (0x21)
            buf.addAll(pk.toList())
        }

        buf.add((0x50 + n).toByte()) // OP_N
        buf.add(0xAE.toByte()) // OP_CHECKMULTISIG

        return buf.toByteArray()
    }

    /*
     * Converts a BIP-32 child key to a Bitcoin address string.
     * Uses BitcoinJ's built-in encoding for P2WPKH.
     * P2TR (Taproot) is not supported by the current BitcoinJ version.
     * P2WSH multisig uses a separate path (deriveMultisigAddresses).
     */
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
            } catch (e: Exception) {
                throw UnsupportedOperationException(
                    "P2TR (Taproot) addresses are not supported by the current BitcoinJ version. " +
                    "Use P2WPKH (wpkh) or P2WSH (wsh) descriptor instead.", e
                )
            }

        DescriptorType.P2WSH_MULTISIG ->
            throw IllegalStateException("P2WSH_MULTISIG should use deriveMultisigAddresses()")
    }

    /* Extracts the first xpub/tpub base58 string from a descriptor. */
    private fun extractXpub(descriptor: String): String? =
        XPUB_RE.find(descriptor)?.value

    /* Detects script type from the descriptor prefix (wpkh/wsh/tr). */
    private fun detectScriptType(descriptor: String): DescriptorType = when {
        (descriptor.contains("wsh(") && descriptor.contains("multi(")) -> DescriptorType.P2WSH_MULTISIG
        descriptor.startsWith("wpkh(") || descriptor.contains("wpkh(") -> DescriptorType.P2WPKH
        descriptor.startsWith("tr(")   || descriptor.contains("tr(")   -> DescriptorType.P2TR
        else -> {
            log.warn("Unknown descriptor prefix, defaulting to P2WPKH: {}", descriptor.take(30))
            DescriptorType.P2WPKH
        }
    }

    /* Maps "mainnet"/"testnet" string to BitcoinJ network constant. */
    private fun bitcoinNetwork(network: String): BitcoinNetwork = when (network.lowercase()) {
        "mainnet", "bitcoin" -> BitcoinNetwork.MAINNET
        else -> BitcoinNetwork.TESTNET
    }

    /* Converts byte array to hex string (used for BIP-67 pubkey sorting). */
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // --- Bech32 encoding (BitcoinJ 0.17 lacks a public segwitToBech32 method) ---

    private val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    /* Encodes a SegWit witness program as a bech32 address (tb1q.../bc1q...). */
    private fun segwitToBech32(hrp: String, witnessVersion: Int, program: ByteArray): String {
        val data = convertBits8to5(program)
        val payload = intArrayOf(witnessVersion) + data
        val checksum = createBech32Checksum(hrp, payload)
        val sb = StringBuilder(hrp.length + 1 + payload.size + checksum.size)
        sb.append(hrp).append('1')
        for (v in payload) sb.append(BECH32_CHARSET[v])
        for (v in checksum) sb.append(BECH32_CHARSET[v])
        return sb.toString()
    }

    /* Converts 8-bit bytes to 5-bit groups for bech32 base-32 encoding. */
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

    /* Bech32 polynomial modular checksum (BIP-173 spec). */
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

    /* Expands the human-readable part ("bc"/"tb") for checksum calculation. */
    private fun bech32HrpExpand(hrp: String): IntArray {
        val result = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) result[i] = hrp[i].code shr 5
        for (i in hrp.indices) result[hrp.length + 1 + i] = hrp[i].code and 31
        return result
    }

    /* Computes the 6-value bech32 checksum for address verification. */
    private fun createBech32Checksum(hrp: String, data: IntArray): IntArray {
        val values = bech32HrpExpand(hrp) + data + intArrayOf(0, 0, 0, 0, 0, 0)
        val polymod = bech32Polymod(values) xor 1
        return IntArray(6) { (polymod shr (5 * (5 - it))) and 31 }
    }

    private enum class DescriptorType { P2WPKH, P2TR, P2WSH_MULTISIG }

    data class DerivedAddress(val index: Int, val address: String)
}
