package cz.majny.wallet.psbt.builder

import org.slf4j.LoggerFactory
import java.util.*

/*
 * Low-level Bitcoin and PSBT (BIP-174) binary encoding and parsing utilities.
 * Contains no business logic — just byte manipulation, hex/bech32 conversions,
 * script construction, and PSBT structural parsing.
 */
object PsbtEncoding {

    private val log = LoggerFactory.getLogger(PsbtEncoding::class.java)

    // PSBT key type constants (BIP-174)
    const val PSBT_IN_NON_WITNESS_UTXO = 0x00
    const val PSBT_IN_WITNESS_UTXO = 0x01
    const val PSBT_IN_WITNESS_SCRIPT = 0x05
    const val PSBT_IN_BIP32_DERIVATION = 0x06
    const val PSBT_OUT_BIP32_DERIVATION = 0x02
    const val PSBT_GLOBAL_UNSIGNED_TX = 0x00

    // ========== BIP-174 Key-Value Encoding ==========

    /*
     * Writes a single BIP-174 key-value pair into the PSBT byte buffer.
     * Key format:   varint(len(keyType + keyData)) || keyType || keyData
     * Value format:  varint(len(value)) || value
     */
    fun writeKv(buf: MutableList<Byte>, keyType: Int, keyData: ByteArray, value: ByteArray) {
        val keyBytes = byteArrayOf(keyType.toByte()) + keyData
        buf.addAll(writeVarInt(keyBytes.size.toLong()))
        buf.addAll(keyBytes.toList())
        buf.addAll(writeVarInt(value.size.toLong()))
        buf.addAll(value.toList())
    }

    // ========== Integer Encoding ==========

    /* Encodes a variable-length integer in Bitcoin CompactSize format. */
    fun writeVarInt(value: Long): List<Byte> = when {
        value < 0xfd -> listOf(value.toByte())
        value <= 0xffff -> listOf(
            0xfd.toByte(),
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte()
        )
        else -> listOf(0xfe.toByte()) + intToLE(value.toInt(), 4)
    }

    /* Encodes an integer as little-endian bytes of the specified width. */
    fun intToLE(value: Int, bytes: Int): List<Byte> =
        (0 until bytes).map { ((value shr (it * 8)) and 0xff).toByte() }

    /* Encodes a long as 8 little-endian bytes. */
    fun longToLE(value: Long): List<Byte> =
        (0 until 8).map { ((value shr (it * 8)) and 0xff).toByte() }

    // ========== Hex Conversion ==========

    /* Converts a hex string to a byte array. */
    fun hexToBytes(hex: String): ByteArray {
        val data = ByteArray(hex.length / 2)
        for (i in data.indices) {
            data[i] = ((Character.digit(hex[2 * i], 16) shl 4) +
                    Character.digit(hex[2 * i + 1], 16)).toByte()
        }
        return data
    }

    /* Converts a byte array to a hex string. */
    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    // ========== Bitcoin Address → Script ==========

    /*
     * Converts a Bitcoin address to its scriptPubKey byte array.
     * Supports P2WPKH (bc1q/tb1q, 20-byte program), P2WSH (bc1q/tb1q, 32-byte program),
     * and P2TR (bc1p/tb1p, 32-byte program).
     */
    fun addressToScript(address: String): ByteArray = when {
        address.startsWith("bc1q") || address.startsWith("tb1q") -> {
            val decoded = bech32Decode(address)
            when (decoded.size) {
                20 -> byteArrayOf(0x00, 0x14) + decoded  // P2WPKH: OP_0 <20 bytes>
                32 -> byteArrayOf(0x00, 0x20) + decoded  // P2WSH:  OP_0 <32 bytes>
                else -> {
                    log.warn("Unexpected witness program length: {} for address {}", decoded.size, address)
                    byteArrayOf(0x00, decoded.size.toByte()) + decoded
                }
            }
        }
        address.startsWith("bc1p") || address.startsWith("tb1p") -> {
            val decoded = bech32Decode(address)
            byteArrayOf(0x51, 0x20) + decoded  // P2TR: OP_1 <32 bytes>
        }
        else -> {
            log.warn("Unknown address format: {}", address)
            ByteArray(0)
        }
    }

    /* Converts a Bitcoin address to its scriptPubKey hex string. */
    fun addressToScriptHex(address: String): String = bytesToHex(addressToScript(address))

    // ========== Witness UTXO / Script Construction ==========

    /* Builds the witness UTXO field (amount + scriptPubKey) for PSBT_IN_WITNESS_UTXO. */
    fun buildWitnessUtxo(value: Long, scriptPubKeyHex: String?): ByteArray {
        val result = mutableListOf<Byte>()
        result.addAll(longToLE(value))
        val scriptBytes = scriptPubKeyHex?.let { hexToBytes(it) } ?: ByteArray(0)
        result.addAll(writeVarInt(scriptBytes.size.toLong()))
        result.addAll(scriptBytes.toList())
        return result.toByteArray()
    }

    /*
     * Builds a multisig witness script (Bitcoin Script bytecode):
     *   OP_M <push 0x21><pubkey1> ... <push 0x21><pubkeyN> OP_N OP_CHECKMULTISIG
     */
    fun buildMultisigWitnessScript(m: Int, pubkeys: List<ByteArray>): ByteArray {
        val n = pubkeys.size
        val buf = mutableListOf<Byte>()
        buf.add((0x50 + m).toByte())  // OP_M (OP_1=0x51, OP_2=0x52, ...)
        for (pk in pubkeys) {
            buf.add(pk.size.toByte()) // push data length (0x21 = 33 for compressed pubkey)
            buf.addAll(pk.toList())
        }
        buf.add((0x50 + n).toByte())  // OP_N
        buf.add(0xAE.toByte())        // OP_CHECKMULTISIG
        return buf.toByteArray()
    }

    /*
     * Encodes a BIP-32 derivation path for PSBT_IN_BIP32_DERIVATION / PSBT_OUT_BIP32_DERIVATION.
     * Format: fingerprint (4 bytes LE) || path_element_1 (uint32 LE) || ... || chain (uint32 LE) || index (uint32 LE)
     */
    fun encodeBip32Derivation(
        fingerprint: String,
        originPath: String,
        chain: Int,
        index: Int
    ): ByteArray {
        val buf = mutableListOf<Byte>()

        // 4-byte master fingerprint
        val fpBytes = hexToBytes(fingerprint.padStart(8, '0').take(8))
        buf.addAll(fpBytes.toList())

        // Parse origin path elements (e.g. "48h/0h/0h/2h" → uint32 LE values with hardened bit)
        val pathParts = originPath.split("/").filter { it.isNotBlank() }
        for (part in pathParts) {
            val cleaned = part.replace("'", "h")
            val hardened = cleaned.endsWith("h")
            val num = cleaned.trimEnd('h').toLong()
            val value = if (hardened) (num or 0x80000000L) else num
            buf.addAll(intToLE(value.toInt(), 4))
        }

        buf.addAll(intToLE(chain, 4))
        buf.addAll(intToLE(index, 4))
        return buf.toByteArray()
    }

    // ========== Bech32 Decoding ==========

    /* Decodes a bech32/bech32m address to its witness program bytes. */
    fun bech32Decode(address: String): ByteArray {
        val charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        val hrpEnd = address.lastIndexOf('1')
        val data = address.substring(hrpEnd + 1).dropLast(6).drop(1)
        val values = data.map { charset.indexOf(it) }
        return convertBits(values, 5, 8)
    }

    /* Converts between bit groups (e.g. 5-bit bech32 values to 8-bit bytes). */
    private fun convertBits(data: List<Int>, fromBits: Int, toBits: Int): ByteArray {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Byte>()
        val maxv = (1 shl toBits) - 1
        for (value in data) {
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add(((acc shr bits) and maxv).toByte())
            }
        }
        return result.toByteArray()
    }

    // ========== PSBT Parsing (BIP-174) ==========

    data class PsbtKV(
        val keyType: Int,
        val keyData: ByteArray,
        val value: ByteArray
    )

    data class ParsedPsbt(
        val unsignedTx: ByteArray,
        val globalKvs: List<PsbtKV>,
        val inputKvs: List<List<PsbtKV>>,
        val outputKvs: List<List<PsbtKV>>
    )

    /* Parses a raw PSBT binary blob into its global, input, and output key-value sections. */
    fun parsePsbt(data: ByteArray): ParsedPsbt {
        require(data.size >= 5 && data[0] == 0x70.toByte() && data[4] == 0xff.toByte()) {
            "Invalid PSBT magic"
        }

        var pos = 5

        // Parse global key-value pairs
        val globalKvs = mutableListOf<PsbtKV>()
        var unsignedTx = ByteArray(0)

        while (pos < data.size) {
            val (keyLen, p1) = readCompactSize(data, pos)
            pos = p1
            if (keyLen == 0L) break

            val keyBytes = data.sliceArray(pos until (pos + keyLen.toInt()))
            pos += keyLen.toInt()

            val (valLen, p2) = readCompactSize(data, pos)
            pos = p2
            val valBytes = data.sliceArray(pos until (pos + valLen.toInt()))
            pos += valLen.toInt()

            val keyType = keyBytes[0].toInt() and 0xFF
            val keyData = if (keyBytes.size > 1) keyBytes.sliceArray(1 until keyBytes.size) else ByteArray(0)

            globalKvs.add(PsbtKV(keyType, keyData, valBytes))
            if (keyType == PSBT_GLOBAL_UNSIGNED_TX) unsignedTx = valBytes
        }

        require(unsignedTx.isNotEmpty()) { "PSBT missing unsigned transaction" }

        val inputCount = countTxInputs(unsignedTx)
        val outputCount = countTxOutputs(unsignedTx)

        // Parse per-input sections
        val inputKvs = mutableListOf<List<PsbtKV>>()
        for (i in 0 until inputCount) {
            val kvs = mutableListOf<PsbtKV>()
            while (pos < data.size) {
                val (keyLen, p1) = readCompactSize(data, pos)
                pos = p1
                if (keyLen == 0L) break

                val keyBytes = data.sliceArray(pos until (pos + keyLen.toInt()))
                pos += keyLen.toInt()
                val (valLen, p2) = readCompactSize(data, pos)
                pos = p2
                val valBytes = data.sliceArray(pos until (pos + valLen.toInt()))
                pos += valLen.toInt()

                val keyType = keyBytes[0].toInt() and 0xFF
                val keyData = if (keyBytes.size > 1) keyBytes.sliceArray(1 until keyBytes.size) else ByteArray(0)
                kvs.add(PsbtKV(keyType, keyData, valBytes))
            }
            inputKvs.add(kvs)
        }

        // Parse per-output sections
        val outputKvs = mutableListOf<List<PsbtKV>>()
        for (i in 0 until outputCount) {
            val kvs = mutableListOf<PsbtKV>()
            while (pos < data.size) {
                val (keyLen, p1) = readCompactSize(data, pos)
                pos = p1
                if (keyLen == 0L) break

                val keyBytes = data.sliceArray(pos until (pos + keyLen.toInt()))
                pos += keyLen.toInt()
                val (valLen, p2) = readCompactSize(data, pos)
                pos = p2
                val valBytes = data.sliceArray(pos until (pos + valLen.toInt()))
                pos += valLen.toInt()

                val keyType = keyBytes[0].toInt() and 0xFF
                val keyData = if (keyBytes.size > 1) keyBytes.sliceArray(1 until keyBytes.size) else ByteArray(0)
                kvs.add(PsbtKV(keyType, keyData, valBytes))
            }
            outputKvs.add(kvs)
        }

        return ParsedPsbt(unsignedTx, globalKvs, inputKvs, outputKvs)
    }

    /* Reads a Bitcoin CompactSize (variable-length integer) from a byte array at the given offset.
     * Returns the value and the new position after reading. */
    fun readCompactSize(data: ByteArray, offset: Int): Pair<Long, Int> {
        val first = data[offset].toInt() and 0xFF
        return when {
            first < 0xFD -> Pair(first.toLong(), offset + 1)
            first == 0xFD -> {
                val v = (data[offset + 1].toInt() and 0xFF) or
                        ((data[offset + 2].toInt() and 0xFF) shl 8)
                Pair(v.toLong(), offset + 3)
            }
            first == 0xFE -> {
                val v = (data[offset + 1].toInt() and 0xFF) or
                        ((data[offset + 2].toInt() and 0xFF) shl 8) or
                        ((data[offset + 3].toInt() and 0xFF) shl 16) or
                        ((data[offset + 4].toInt() and 0xFF) shl 24)
                Pair(v.toLong() and 0xFFFFFFFFL, offset + 5)
            }
            else -> {
                var v = 0L
                for (i in 0 until 8) {
                    v = v or ((data[offset + 1 + i].toLong() and 0xFF) shl (i * 8))
                }
                Pair(v, offset + 9)
            }
        }
    }

    /* Counts transaction inputs from the unsigned transaction bytes. */
    fun countTxInputs(tx: ByteArray): Int {
        val (count, _) = readCompactSize(tx, 4) // skip version (4 bytes)
        return count.toInt()
    }

    /* Counts transaction outputs from the unsigned transaction bytes. */
    fun countTxOutputs(tx: ByteArray): Int {
        var pos = 4 // skip version
        val (inputCount, p1) = readCompactSize(tx, pos)
        pos = p1

        // Skip all inputs: prevout hash (32) + index (4) + scriptSig (var) + sequence (4)
        for (i in 0 until inputCount.toInt()) {
            pos += 32 + 4
            val (scriptLen, p2) = readCompactSize(tx, pos)
            pos = p2
            pos += scriptLen.toInt()
            pos += 4
        }

        val (outputCount, _) = readCompactSize(tx, pos)
        return outputCount.toInt()
    }
}
