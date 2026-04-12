package com.example.bitcoinwallet.core.ur

import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.registry.CryptoAccount
import com.sparrowwallet.hummingbird.registry.CryptoCoinInfo
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.CryptoOutput
import com.sparrowwallet.hummingbird.registry.MultiKey
import com.sparrowwallet.hummingbird.registry.ScriptExpression
import com.sparrowwallet.hummingbird.registry.pathcomponent.IndexPathComponent
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Converts a decoded UR (from Sparrow / Keystone / Passport / etc.) into a
 * BIP-380 output descriptor string that our backend DescriptorParser accepts.
 *
 * Supports `crypto-output` (multisig + singlesig), `crypto-account`
 * (picks the first wpkh descriptor by default), and `crypto-hdkey`
 * (defaults to wpkh wrapper).
 *
 * Hummingbird decodes the UR + CBOR but does not rebuild the base58 xpub,
 * so we reconstruct the 78-byte BIP-32 serialization here.
 */
object UrDescriptorDecoder {

    fun decode(ur: UR): String {
        return when (ur.type) {
            "crypto-output" -> renderCryptoOutput(ur.decodeFromRegistry() as CryptoOutput)
            "crypto-account" -> renderCryptoAccount(ur.decodeFromRegistry() as CryptoAccount)
            "crypto-hdkey" -> {
                val hdKey = ur.decodeFromRegistry() as CryptoHDKey
                "wpkh(${renderHdKeyWithOrigin(hdKey)})"
            }
            else -> throw IllegalArgumentException("Unsupported UR type: ${ur.type}")
        }
    }

    private fun renderCryptoAccount(account: CryptoAccount): String {
        val outputs = account.outputDescriptors
        if (outputs.isEmpty()) {
            throw IllegalArgumentException("crypto-account has no output descriptors")
        }
        // Prefer native segwit singlesig (wpkh), otherwise first descriptor.
        val preferred = outputs.firstOrNull { out ->
            out.scriptExpressions.any { it == ScriptExpression.WITNESS_PUBLIC_KEY_HASH }
        } ?: outputs.first()
        return renderCryptoOutput(preferred)
    }

    private fun renderCryptoOutput(output: CryptoOutput): String {
        val expressions = output.scriptExpressions
        val inner: String = when {
            output.multiKey != null -> renderMultiKey(output.multiKey, expressions)
            output.hdKey != null -> renderHdKeyWithOrigin(output.hdKey)
            output.ecKey != null -> throw IllegalArgumentException("Raw EC keys unsupported")
            else -> throw IllegalArgumentException("CryptoOutput has no key payload")
        }
        // Wrap by script expressions, outermost first. Multisig keywords are
        // already baked into the inner fragment by renderMultiKey().
        var acc = inner
        for (i in expressions.size - 1 downTo 0) {
            val exp = expressions[i]
            if (exp == ScriptExpression.SORTED_MULTISIG || exp == ScriptExpression.MULTISIG) continue
            val keyword = wrapperKeyword(exp)
            acc = "$keyword($acc)"
        }
        return acc
    }

    private fun wrapperKeyword(exp: ScriptExpression): String = when (exp) {
        ScriptExpression.SCRIPT_HASH -> "sh"
        ScriptExpression.WITNESS_SCRIPT_HASH -> "wsh"
        ScriptExpression.WITNESS_PUBLIC_KEY_HASH -> "wpkh"
        ScriptExpression.PUBLIC_KEY_HASH -> "pkh"
        ScriptExpression.TAPROOT -> "tr"
        ScriptExpression.PUBLIC_KEY -> "pk"
        else -> throw IllegalArgumentException("Unsupported script wrapper: $exp")
    }

    private fun renderMultiKey(multiKey: MultiKey, expressions: List<ScriptExpression>): String {
        val keyword = if (expressions.contains(ScriptExpression.SORTED_MULTISIG)) "sortedmulti" else "multi"
        val keys = multiKey.hdKeys.joinToString(",") { renderHdKeyWithOrigin(it) }
        return "$keyword(${multiKey.threshold},$keys)"
    }

    private fun renderHdKeyWithOrigin(hdKey: CryptoHDKey): String {
        val origin = hdKey.origin
            ?: throw IllegalArgumentException("HDKey missing origin keypath")
        val sourceFp = origin.sourceFingerprint
            ?: throw IllegalArgumentException("Origin keypath missing source fingerprint")
        val fingerprint = bytesToHex(sourceFp)
        val originPath = origin.path ?: ""
        val xpub = reconstructXpub(hdKey)
        val childrenPath = hdKey.children?.path?.let { "/$it" } ?: "/0/*"
        return "[$fingerprint/$originPath]$xpub$childrenPath"
    }

    /**
     * Rebuild the base58 xpub/tpub from CryptoHDKey fields.
     *
     * BIP-32 serialization: version(4) || depth(1) || parentFp(4) ||
     * childNumber(4) || chainCode(32) || keyData(33), then base58check.
     */
    private fun reconstructXpub(hdKey: CryptoHDKey): String {
        val testnet = hdKey.useInfo?.network == CryptoCoinInfo.Network.TESTNET
        val version = if (testnet) 0x043587CF else 0x0488B21E

        val components = hdKey.origin?.components.orEmpty()
        val depth = (hdKey.origin?.depth ?: components.size).coerceIn(0, 255).toByte()

        val childNumber: Int = when (val last = components.lastOrNull()) {
            null -> 0
            is IndexPathComponent -> {
                val idx = last.index
                if (last.isHardened) idx or 0x80000000.toInt() else idx
            }
            else -> throw IllegalArgumentException(
                "Last origin component must be a concrete index, got ${last.javaClass.simpleName}"
            )
        }

        val parentFp = hdKey.parentFingerprint ?: ByteArray(4)
        if (parentFp.size != 4) {
            throw IllegalArgumentException("parentFingerprint must be 4 bytes, got ${parentFp.size}")
        }
        val chainCode = hdKey.chainCode
            ?: throw IllegalArgumentException("HDKey missing chain code")
        if (chainCode.size != 32) {
            throw IllegalArgumentException("chainCode must be 32 bytes, got ${chainCode.size}")
        }
        val keyData = hdKey.key
            ?: throw IllegalArgumentException("HDKey missing key data")
        if (keyData.size != 33) {
            throw IllegalArgumentException("key must be 33 bytes, got ${keyData.size}")
        }

        val buf = ByteBuffer.allocate(78)
        buf.putInt(version)
        buf.put(depth)
        buf.put(parentFp)
        buf.putInt(childNumber)
        buf.put(chainCode)
        buf.put(keyData)
        val raw = buf.array()

        val checksum = sha256(sha256(raw)).copyOf(4)
        return base58Encode(raw + checksum)
    }

    // ─── Base58Check helpers ───────────────────────────────────────────

    private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private fun base58Encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var leadingZeros = 0
        while (leadingZeros < input.size && input[leadingZeros].toInt() == 0) leadingZeros++

        var num = BigInteger(1, input)
        val sb = StringBuilder()
        val base = BigInteger.valueOf(58)
        while (num > BigInteger.ZERO) {
            val qr = num.divideAndRemainder(base)
            sb.append(BASE58_ALPHABET[qr[1].toInt()])
            num = qr[0]
        }
        repeat(leadingZeros) { sb.append(BASE58_ALPHABET[0]) }
        return sb.reverse().toString()
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
