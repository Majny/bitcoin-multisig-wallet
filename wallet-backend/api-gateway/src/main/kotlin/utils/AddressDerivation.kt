package cz.majny.wallet.gateway.utils

import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Utility for deriving Bitcoin addresses from output descriptors.
 * 
 * For MVP, this generates deterministic placeholder addresses based on descriptor hash.
 * In production, integrate with a proper Bitcoin library like rust-bitcoin via JNI
 * or use a separate address derivation microservice.
 * 
 * Supports:
 * - wpkh([fingerprint/path]xpub/0/star) - Native SegWit P2WPKH
 * - tr([fingerprint/path]xpub/0/star) - Taproot P2TR  
 * - wsh(sortedmulti(m,...)) - Multisig
 */
object AddressDerivation {
    
    private val log = LoggerFactory.getLogger(AddressDerivation::class.java)
    
    // Regex to extract xpub from descriptor
    private val XPUB_PATTERN = Regex("""([xtXTvV]pub[A-Za-z0-9]{100,120})""")
    
    /**
     * Derives a receive address from an output descriptor.
     * 
     * NOTE: This is a placeholder implementation for MVP.
     * It generates a deterministic "address" based on the descriptor hash,
     * which is useful for development and testing.
     * 
     * For production, implement proper BIP32/BIP84/BIP86 derivation using:
     * - JNI bindings to libsecp256k1 and rust-bitcoin
     * - Or a dedicated address derivation microservice
     * 
     * @param descriptor Output descriptor, e.g., wpkh(xpub.../0/index)
     * @param index Address index (0 for first address)
     * @param network "mainnet" or "testnet"
     * @return Derived Bitcoin address (placeholder format for MVP)
     */
    fun deriveAddress(descriptor: String, index: Int, network: String): String {
        val scriptType = detectScriptType(descriptor)
        val isMainnet = network.lowercase() == "mainnet"
        
        // Extract xpub and create deterministic address
        val xpub = extractXpub(descriptor)
        val hash = hashDescriptorWithIndex(descriptor, index)
        
        log.debug("Deriving address: scriptType={}, index={}, network={}", scriptType, index, network)
        
        return when (scriptType) {
            ScriptType.P2WPKH -> {
                // Native SegWit - bc1q... (mainnet) or tb1q... (testnet)
                val prefix = if (isMainnet) "bc1q" else "tb1q"
                "${prefix}${hash.take(38)}" // P2WPKH is 42 chars total
            }
            ScriptType.P2TR -> {
                // Taproot - bc1p... (mainnet) or tb1p... (testnet)
                val prefix = if (isMainnet) "bc1p" else "tb1p"
                "${prefix}${hash.take(58)}" // P2TR is 62 chars total
            }
            ScriptType.P2WSH -> {
                // Wrapped SegWit Multisig - bc1q... but longer
                val prefix = if (isMainnet) "bc1q" else "tb1q"
                "${prefix}${hash.take(58)}" // P2WSH is 62 chars total
            }
        }
    }
    
    /**
     * Create a deterministic hash of descriptor + index for address generation.
     * Uses lowercase hex for bech32-like appearance.
     */
    private fun hashDescriptorWithIndex(descriptor: String, index: Int): String {
        val input = "$descriptor:$index"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        
        // Convert to bech32-compatible characters (lowercase alphanumeric without 1, b, i, o)
        val bech32Chars = "023456789acdefghjklmnpqrstuvwxyz"
        return hash.map { byte ->
            bech32Chars[(byte.toInt() and 0xFF) % bech32Chars.length]
        }.joinToString("")
    }
    
    private fun extractXpub(descriptor: String): String? {
        return XPUB_PATTERN.find(descriptor)?.value
    }
    
    private fun detectScriptType(descriptor: String): ScriptType {
        return when {
            descriptor.startsWith("wpkh(") -> ScriptType.P2WPKH
            descriptor.startsWith("tr(") -> ScriptType.P2TR
            descriptor.startsWith("wsh(") -> ScriptType.P2WSH
            descriptor.contains("wpkh(") -> ScriptType.P2WPKH
            descriptor.contains("tr(") -> ScriptType.P2TR
            descriptor.contains("wsh(") -> ScriptType.P2WSH
            else -> {
                log.warn("Unknown descriptor type, defaulting to P2WPKH: ${descriptor.take(30)}")
                ScriptType.P2WPKH
            }
        }
    }
    
    private enum class ScriptType {
        P2WPKH,  // Native SegWit (bc1q...)
        P2TR,    // Taproot (bc1p...)
        P2WSH    // Wrapped SegWit Multisig
    }
}
