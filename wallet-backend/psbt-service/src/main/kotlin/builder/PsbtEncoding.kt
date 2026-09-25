package cz.majny.wallet.psbt.builder

import java.util.*

/*
 * Low-level Bitcoin and PSBT (BIP-174) binary encoding and parsing utilities.
 * Contains no business logic - just byte manipulation, hex/bech32 conversions,
 * script construction, and PSBT structural parsing.
 */
object PsbtEncoding {

    // PSBT key type constants (BIP-174)
    const val PSBT_IN_NON_WITNESS_UTXO = 0x00
    const val PSBT_IN_WITNESS_UTXO = 0x01
    const val PSBT_IN_WITNESS_SCRIPT = 0x05
    const val PSBT_IN_BIP32_DERIVATION = 0x06
    const val PSBT_OUT_BIP32_DERIVATION = 0x02
    const val PSBT_GLOBAL_UNSIGNED_TX = 0x00

    // BIP-174 Key-Value Encoding

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

    // Integer Encoding

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

    // Hex Conversion

    /* Converts a hex string to a byte array. */
    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length, got ${hex.length}" }
        val data = ByteArray(hex.length / 2)
        for (i in data.indices) {
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex character at position ${2 * i}" }
            data[i] = ((hi shl 4) + lo).toByte()
        }
        return data
    }

    /* Converts a byte array to a hex string. */
    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    // Bitcoin Address → Script

    /*
     * Converts a Bitcoin address to its scriptPubKey byte array.
     * Segwit (bc1/tb1, any witness version, e.g. P2WPKH, P2WSH, P2TR) and
     * Base58Check P2PKH / P2SH. Every address is checksum-validated first.
     */
    fun addressToScript(address: String): ByteArray = when {
        address.lowercase().let { it.startsWith("bc1") || it.startsWith("tb1") } -> {
            val (version, program) = decodeSegwitAddress(address)
            // OP_0 or OP_1..OP_16 (0x51..0x60), then a direct push of the program
            val versionOp = if (version == 0) 0x00 else 0x50 + version
            byteArrayOf(versionOp.toByte(), program.size.toByte()) + program
        }
        // P2PKH: mainnet (1...) or testnet (m.../n...)
        address.startsWith("1") || address.startsWith("m") || address.startsWith("n") -> {
            val hash = base58CheckDecode(address, 0x00, 0x6f)
            // OP_DUP OP_HASH160 <20 bytes> OP_EQUALVERIFY OP_CHECKSIG
            byteArrayOf(0x76.toByte(), 0xa9.toByte(), 0x14) + hash +
                    byteArrayOf(0x88.toByte(), 0xac.toByte())
        }
        // P2SH: mainnet (3...) or testnet (2...)
        address.startsWith("3") || address.startsWith("2") -> {
            val hash = base58CheckDecode(address, 0x05, 0xc4)
            // OP_HASH160 <20 bytes> OP_EQUAL
            byteArrayOf(0xa9.toByte(), 0x14) + hash + byteArrayOf(0x87.toByte())
        }
        else -> {
            throw IllegalArgumentException("Unsupported address format: $address")
        }
    }

    /* Converts a Bitcoin address to its scriptPubKey hex string. */
    fun addressToScriptHex(address: String): String = bytesToHex(addressToScript(address))

    // Witness UTXO / Script Construction

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

    // Segwit Address Decoding (BIP-173 bech32, BIP-350 bech32m)

    private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32_CONST = 1            // BIP-173 checksum, witness version 0
    private const val BECH32M_CONST = 0x2bc830a3  // BIP-350 checksum, witness version 1..16
    private val SEGWIT_HRPS = setOf("bc", "tb")   // mainnet, testnet

    /*
     * Decodes a segwit address into (witness version, witness program), enforcing:
     * US-ASCII 33..126 only, a single case, at most 90 chars, a known HRP, only bech32
     * characters, version 0..16, a bech32 checksum for v0 and bech32m for v1+, at most
     * 4 bits of zero padding, a 2..40 byte program, and 20 or 32 bytes for v0 (BIP-141).
     */
    fun decodeSegwitAddress(address: String): Pair<Int, ByteArray> {
        require(address.length <= 90) { "Invalid bech32 address: longer than 90 characters" }
        // Checked before any case folding: U+212A (Kelvin sign) lowercases to 'k'
        require(address.all { it.code in 33..126 }) { "Invalid bech32 address: non-ASCII character" }
        require(address == address.lowercase() || address == address.uppercase()) {
            "Invalid bech32 address: mixed case in $address"
        }
        val lower = address.lowercase()
        val sep = lower.lastIndexOf('1')
        // Separator after a non-empty HRP, followed by the version and a 6-char checksum
        require(sep >= 1 && lower.length - sep - 1 >= 7) { "Invalid bech32 address: data part too short" }
        val hrp = lower.substring(0, sep)
        require(hrp in SEGWIT_HRPS) { "Invalid bech32 address: unknown prefix '$hrp'" }
        val values = lower.substring(sep + 1).map { c ->
            val idx = BECH32_CHARSET.indexOf(c)
            require(idx >= 0) { "Invalid bech32 character: '$c' in address $address" }
            idx
        }
        val version = values[0]
        require(version <= 16) { "Invalid witness version $version in address $address" }
        val expected = if (version == 0) BECH32_CONST else BECH32M_CONST
        require(bech32Polymod(hrpExpand(hrp) + values) == expected) {
            "Invalid bech32 checksum in address $address"
        }
        // Drop witness version (first value) and checksum (last 6 values)
        val program = convertBits(values.subList(1, values.size - 6), 5, 8)
        require(program.size in 2..40) { "Invalid witness program length ${program.size} in address $address" }
        require(version != 0 || program.size == 20 || program.size == 32) {
            "Invalid v0 witness program length ${program.size} in address $address"
        }
        return version to program
    }

    /* BIP-173 HRP expansion: high bits of each char, a zero, then the low 5 bits. */
    private fun hrpExpand(hrp: String): List<Int> =
        hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 }

    /* BIP-173 BCH checksum polymod over 5-bit values. */
    private fun bech32Polymod(values: List<Int>): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) {
                if ((top shr i) and 1 == 1) chk = chk xor gen[i]
            }
        }
        return chk
    }

    // Base58Check Decoding

    private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /* Decodes a Base58Check address and returns its 20-byte hash. The decoded bytes must be
     * version (1) + hash (20) + checksum (4), the version must be one of [versions], and the
     * checksum must equal the first 4 bytes of SHA-256(SHA-256(version + hash)). */
    fun base58CheckDecode(address: String, vararg versions: Int): ByteArray {
        var num = java.math.BigInteger.ZERO
        val base = java.math.BigInteger.valueOf(58)
        for (c in address) {
            val digit = BASE58_ALPHABET.indexOf(c)
            require(digit >= 0) { "Invalid Base58 character: '$c' in address $address" }
            num = num.multiply(base).add(java.math.BigInteger.valueOf(digit.toLong()))
        }
        // Each leading '1' encodes a zero byte; dropWhile strips BigInteger's sign byte
        val leadingZeros = address.takeWhile { it == '1' }.length
        val bytes = ByteArray(leadingZeros) + num.toByteArray().dropWhile { it == 0.toByte() }
        require(bytes.size == 25) { "Invalid Base58Check address length: $address" }
        require((bytes[0].toInt() and 0xff) in versions) { "Unexpected Base58Check version byte in address $address" }
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
        val checksum = sha256.digest(sha256.digest(bytes.copyOfRange(0, 21))).copyOfRange(0, 4)
        require(checksum.contentEquals(bytes.copyOfRange(21, 25))) { "Invalid Base58Check checksum in address $address" }
        return bytes.copyOfRange(1, 21)
    }

    /* Regroups 5-bit bech32 values into bytes. Per BIP-173 the leftover padding
     * must be at most 4 bits and all zero. */
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
        require(bits < fromBits && ((acc shl (toBits - bits)) and maxv) == 0) {
            "Invalid bech32 padding"
        }
        return result.toByteArray()
    }

    // Raw Transaction Parsing

    data class ParsedRawTx(
        val version: Int,
        val inputs: List<ParsedTxInput>,
        val outputs: List<ParsedTxOutput>,
        val lockTime: Int
    )

    data class ParsedTxInput(
        val prevHash: String,   // txid hex (big-endian display order)
        val prevIndex: Long,
        val scriptSig: String,  // hex
        val sequence: Long
    )

    data class ParsedTxOutput(
        val amount: Long,
        val scriptPubKey: String // hex
    )

    /*
     * Parses a raw Bitcoin transaction hex (segwit or legacy) into structured fields.
     * For segwit transactions, skips the witness marker/flag and witness data.
     * Used to build Trezor Connect refTxs in structured format.
     */
    fun parseRawTransaction(hex: String): ParsedRawTx {
        val data = hexToBytes(hex)
        var pos = 0

        // Version (4 bytes LE)
        val version = readUint32LE(data, pos).toInt()
        pos += 4

        // Detect segwit marker (0x00 0x01)
        val isSegwit = data.size > pos + 1 && data[pos] == 0x00.toByte() && data[pos + 1] == 0x01.toByte()
        if (isSegwit) pos += 2

        // Inputs
        val (inputCount, p1) = readCompactSize(data, pos)
        pos = p1
        val inputs = mutableListOf<ParsedTxInput>()
        for (i in 0 until inputCount.toInt()) {
            // prev_hash (32 bytes, stored LE in tx, display as BE)
            val prevHashBytes = data.sliceArray(pos until pos + 32).reversedArray()
            val prevHash = bytesToHex(prevHashBytes)
            pos += 32

            val prevIndex = readUint32LE(data, pos)
            pos += 4

            val (scriptLen, p2) = readCompactSize(data, pos)
            pos = p2
            val scriptSig = bytesToHex(data.sliceArray(pos until pos + scriptLen.toInt()))
            pos += scriptLen.toInt()

            val sequence = readUint32LE(data, pos)
            pos += 4

            inputs.add(ParsedTxInput(prevHash, prevIndex, scriptSig, sequence))
        }

        // Outputs
        val (outputCount, p3) = readCompactSize(data, pos)
        pos = p3
        val outputs = mutableListOf<ParsedTxOutput>()
        for (i in 0 until outputCount.toInt()) {
            val amount = readUint64LE(data, pos)
            pos += 8

            val (scriptLen, p4) = readCompactSize(data, pos)
            pos = p4
            val scriptPubKey = bytesToHex(data.sliceArray(pos until pos + scriptLen.toInt()))
            pos += scriptLen.toInt()

            outputs.add(ParsedTxOutput(amount, scriptPubKey))
        }

        // Skip witness data if segwit (we don't need it for refTxs)
        // Lock time is at the very end of the raw tx
        val lockTime = readUint32LE(data, data.size - 4).toInt()

        return ParsedRawTx(version, inputs, outputs, lockTime)
    }

    /* Reads a uint32 (4 bytes LE) as unsigned Long. */
    private fun readUint32LE(data: ByteArray, offset: Int): Long {
        return (data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    /* Reads a uint64 (8 bytes LE) as Long. */
    private fun readUint64LE(data: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((data[offset + i].toLong() and 0xFF) shl (i * 8))
        }
        return v
    }

    // PSBT Parsing (BIP-174)

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
