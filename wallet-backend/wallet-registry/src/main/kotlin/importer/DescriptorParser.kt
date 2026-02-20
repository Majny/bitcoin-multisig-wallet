package cz.majny.wallet.registry.importer

import org.slf4j.LoggerFactory
import java.security.MessageDigest

//
// Parses Bitcoin output descriptors into structured wallet definitions.
//
// Supported formats:
//   - wsh(sortedmulti(M, [fp/path]xpub/chain/STAR, ...))  -> P2WSH multisig
//   - wsh(multi(M, [fp/path]xpub/chain/STAR, ...))         -> P2WSH multisig (unsorted)
//   - sh(wsh(sortedmulti(M, ...)))                         -> P2SH-P2WSH multisig
//   - wpkh([fp/path]xpub/chain/STAR)                       -> P2WPKH single-sig
//   - tr([fp/path]xpub/chain/STAR)                         -> P2TR single-sig
//
// BIP-67 sorting: For sortedmulti, cosigner xpubs are sorted lexicographically
// by their serialized public key at each derivation index.
//
object DescriptorParser {

    private val log = LoggerFactory.getLogger(DescriptorParser::class.java)

    // Regex for a single key origin + xpub inside a descriptor.
    // Captures: [fingerprint/derivation_path]xpub.../chain_and_wildcard
    //
    // Group 1: fingerprint (hex, 8 chars)
    // Group 2: origin derivation path (e.g. 48'/0'/0'/2')
    // Group 3: xpub (or tpub) base58 string
    // Group 4: trailing path (e.g. /0/STAR or /STAR)
    private val KEY_ORIGIN_RE = Regex(
        """\[([0-9a-fA-F]{8})/([^\]]+)\]([xtX]pub[1-9A-HJ-NP-Za-km-z]{79,120})(/[0-9*/<>;{}]+)?"""
    )

    // Regex to extract M from multi(M, ...) or sortedmulti(M, ...)
    private val MULTI_M_RE = Regex("""(?:sorted)?multi\((\d+)\s*,""")

    // Descriptor checksum regex: #checksum at the end.
    private val CHECKSUM_RE = Regex("""#([0-9a-z]{8})\s*$""")

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Parse a raw descriptor string into a [ParsedDescriptor].
     *
     * Accepts either:
     *   - A single descriptor (receive or change) — we'll generate the counterpart
     *   - Two descriptors separated by newline
     *
     * @param raw The descriptor string (may include checksum)
     * @param network "mainnet" or "testnet" (for validation)
     * @param label Optional label for the wallet
     * @return [ParsedDescriptor] with all extracted info
     * @throws DescriptorParseException on invalid input
     */
    fun parse(raw: String, network: String = "mainnet", label: String? = null): ParsedDescriptor {
        val lines = raw.trim().lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        // If two lines provided, treat as receive + change descriptors
        val (receiveRaw, changeRaw) = when (lines.size) {
            1 -> {
                val d = stripChecksum(lines[0])
                Pair(d, deriveCounterpart(d))
            }
            2 -> Pair(stripChecksum(lines[0]), stripChecksum(lines[1]))
            else -> throw DescriptorParseException("Expected 1 or 2 descriptor lines, got ${lines.size}")
        }

        log.info("Parsing descriptor: receive={}", receiveRaw.take(80))

        val scriptType = detectScriptType(receiveRaw)
        val isMultisig = receiveRaw.contains("multi(")

        return if (isMultisig) {
            parseMultisig(receiveRaw, changeRaw, scriptType, network, label)
        } else {
            parseSinglesig(receiveRaw, changeRaw, scriptType, network, label)
        }
    }

    // ------------------------------------------------------------------
    // Multisig parsing
    // ------------------------------------------------------------------

    private fun parseMultisig(
        receiveDesc: String,
        changeDesc: String,
        scriptType: ScriptTypeInfo,
        network: String,
        label: String?
    ): ParsedDescriptor {
        // Extract M
        val mMatch = MULTI_M_RE.find(receiveDesc)
            ?: throw DescriptorParseException("Cannot find M in multi(): $receiveDesc")
        val m = mMatch.groupValues[1].toInt()

        val isSorted = receiveDesc.contains("sortedmulti(")

        // Extract all key origins
        val keys = KEY_ORIGIN_RE.findAll(receiveDesc).map { match ->
            ParsedCosigner(
                fingerprint = match.groupValues[1].lowercase(),
                originPath = match.groupValues[2],
                xpub = match.groupValues[3],
                trailingPath = match.groupValues[4].ifEmpty { "/*" }
            )
        }.toList()

        if (keys.isEmpty()) {
            throw DescriptorParseException("No key origins found in descriptor")
        }

        val n = keys.size
        if (m < 1 || m > n) {
            throw DescriptorParseException("Invalid M-of-N: $m of $n")
        }

        // BIP-67: sort cosigners by xpub for canonical ordering
        val sortedKeys = if (isSorted) {
            keys.sortedBy { it.xpub }
        } else {
            keys
        }

        // Generate deterministic wallet ID from descriptor content
        val walletId = generateWalletId(receiveDesc, network)

        // Validate change descriptor has same structure
        val changeKeys = KEY_ORIGIN_RE.findAll(changeDesc).toList()
        if (changeKeys.size != n) {
            log.warn("Change descriptor has {} keys, expected {} — using generated change", changeKeys.size, n)
        }

        log.info("Parsed multisig: {}of{}, script={}, sorted={}, keys={}",
            m, n, scriptType.name, isSorted, sortedKeys.map { it.fingerprint })

        return ParsedDescriptor(
            walletId = walletId,
            network = network,
            type = "MULTI_SIG",
            scriptType = scriptType.name,
            m = m,
            n = n,
            accountIndex = extractAccountIndex(sortedKeys.first().originPath),
            label = label ?: "${m}of${n} Multisig",
            receiveDescriptor = receiveDesc,
            changeDescriptor = changeDesc,
            cosigners = sortedKeys.mapIndexed { idx, key ->
                ParsedCosigner(
                    fingerprint = key.fingerprint,
                    originPath = key.originPath,
                    xpub = key.xpub,
                    trailingPath = key.trailingPath,
                    idx = idx
                )
            }
        )
    }

    // ------------------------------------------------------------------
    // Single-sig parsing
    // ------------------------------------------------------------------

    private fun parseSinglesig(
        receiveDesc: String,
        changeDesc: String,
        scriptType: ScriptTypeInfo,
        network: String,
        label: String?
    ): ParsedDescriptor {
        val keyMatch = KEY_ORIGIN_RE.find(receiveDesc)
            ?: throw DescriptorParseException("No key origin found in single-sig descriptor")

        val fingerprint = keyMatch.groupValues[1].lowercase()
        val originPath = keyMatch.groupValues[2]
        val xpub = keyMatch.groupValues[3]

        val walletId = generateWalletId(receiveDesc, network)

        return ParsedDescriptor(
            walletId = walletId,
            network = network,
            type = "SINGLE_SIG",
            scriptType = scriptType.name,
            m = null,
            n = null,
            accountIndex = extractAccountIndex(originPath),
            label = label ?: "Singlesig (${scriptType.name})",
            receiveDescriptor = receiveDesc,
            changeDescriptor = changeDesc,
            cosigners = listOf(
                ParsedCosigner(
                    fingerprint = fingerprint,
                    originPath = originPath,
                    xpub = xpub,
                    trailingPath = "/0/*",
                    idx = 0
                )
            )
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Strip the #checksum suffix from a descriptor.
     */
    private fun stripChecksum(desc: String): String =
        CHECKSUM_RE.replace(desc, "").trim()

    // Derive the change descriptor from a receive descriptor.
    // Replaces /0/STAR with /1/STAR in the trailing paths.
    private fun deriveCounterpart(receiveDesc: String): String {
        // For descriptors ending with /0/* → replace with /1/*
        val result = receiveDesc.replace("/0/*", "/1/*")
        if (result == receiveDesc) {
            log.warn("Could not derive change descriptor — no /0/* found, using receive as-is")
        }
        return result
    }

    private fun detectScriptType(descriptor: String): ScriptTypeInfo {
        val lower = descriptor.lowercase()
        return when {
            lower.startsWith("sh(wsh(") || lower.contains("sh(wsh(") -> ScriptTypeInfo.SH_WSH
            lower.startsWith("wsh(") || lower.contains("wsh(")       -> ScriptTypeInfo.WSH
            lower.startsWith("wpkh(") || lower.contains("wpkh(")     -> ScriptTypeInfo.WPKH
            lower.startsWith("tr(") || lower.contains("tr(")         -> ScriptTypeInfo.TR
            else -> throw DescriptorParseException("Unsupported descriptor type: ${descriptor.take(30)}")
        }
    }

    /**
     * Extract account index from an origin path like "48'/0'/0'/2'" → account = 0 (3rd segment).
     * For BIP-48 multisig: m/48'/coin'/account'/script'
     * For BIP-84 singlesig: m/84'/coin'/account'
     */
    private fun extractAccountIndex(originPath: String): Int {
        val segments = originPath.replace("'", "h").replace("h", "").split("/")
        // For 48'/0'/0'/2' → segments = [48, 0, 0, 2] → account = segments[2]
        // For 84'/0'/0'    → segments = [84, 0, 0]    → account = segments[2]
        return if (segments.size >= 3) {
            segments[2].toIntOrNull() ?: 0
        } else {
            0
        }
    }

    /**
     * Generate a deterministic wallet ID from the descriptor.
     * Uses SHA-256 of the normalized receive descriptor.
     */
    private fun generateWalletId(receiveDesc: String, network: String): String {
        val input = "$network:$receiveDesc"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        // First 16 hex chars = 8 bytes of entropy, good enough for a wallet ID
        return "w-" + digest.take(8).joinToString("") { "%02x".format(it) }
    }
}

// ------------------------------------------------------------------
// Data classes
// ------------------------------------------------------------------

data class ParsedDescriptor(
    val walletId: String,
    val network: String,
    val type: String,             // SINGLE_SIG or MULTI_SIG
    val scriptType: String,       // WPKH, WSH, SH_WSH, TR
    val m: Int?,
    val n: Int?,
    val accountIndex: Int,
    val label: String?,
    val receiveDescriptor: String,
    val changeDescriptor: String,
    val cosigners: List<ParsedCosigner>
)

data class ParsedCosigner(
    val fingerprint: String,
    val originPath: String,
    val xpub: String,
    val trailingPath: String = "/*",
    val idx: Int = 0
) {
    /** Deterministic cosigner ID from fingerprint + origin path. */
    val cosignerId: String
        get() = "cs-${fingerprint}-${originPath.replace("'", "h").replace("/", "_")}"
}

enum class ScriptTypeInfo {
    WPKH,   // P2WPKH (native segwit singlesig)
    TR,     // P2TR (taproot)
    WSH,    // P2WSH (native segwit multisig)
    SH_WSH  // P2SH-P2WSH (wrapped segwit multisig)
}

class DescriptorParseException(message: String) : IllegalArgumentException(message)
