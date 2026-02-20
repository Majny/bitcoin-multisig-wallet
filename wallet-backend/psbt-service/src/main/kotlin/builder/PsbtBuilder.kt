package cz.majny.wallet.psbt.builder

import cz.majny.wallet.psbt.api.*
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.*

/**
 * PSBT Builder - staví Bitcoin transakce ve formátu PSBT (BIP-174).
 * 
 * Podporuje:
 * - Single-sig P2WPKH transakce
 * - Multisig P2WSH transakce (M-of-N)
 * 
 * POZNÁMKA: Tato implementace vytváří PSBT strukturu manuálně.
 * Pro produkční nasazení zvážit rust-bitcoin přes JNI nebo Bitcoin Core RPC.
 */
object PsbtBuilder {
    
    private val log = LoggerFactory.getLogger(PsbtBuilder::class.java)
    
    /**
     * Vytvoří PSBT transakci.
     */
    fun createPsbt(
        wallet: WalletDetailDto,
        utxos: List<SelectedUtxo>,
        outputs: List<TxOutput>,
        changeAddress: String,
        feeRate: Double,
        rbf: Boolean = true
    ): PsbtBuildResult {
        log.info("Building PSBT: {} inputs, {} outputs, fee rate {} sats/vB",
            utxos.size, outputs.size, feeRate)
        
        // Vypočítej celkový vstup a výstup
        val totalInput = utxos.sumOf { it.value }
        val totalOutput = outputs.sumOf { it.amountSats }
        
        // Odhadni velikost transakce pro výpočet fee
        val estimatedVsize = estimateVsize(
            inputCount = utxos.size,
            outputCount = outputs.size + 1, // +1 pro change
            isMultisig = wallet.type == "multisig",
            m = wallet.m ?: 1,
            n = wallet.n ?: 1
        )
        
        val fee = (estimatedVsize * feeRate).toLong()
        val changeAmount = totalInput - totalOutput - fee
        
        log.info("Total input: {} sats, output: {} sats, fee: {} sats, change: {} sats",
            totalInput, totalOutput, fee, changeAmount)
        
        if (changeAmount < 0) {
            throw IllegalArgumentException(
                "Insufficient funds: input=$totalInput, output=$totalOutput, fee=$fee"
            )
        }
        
        // Dust limit check
        val dustLimit = 546L
        val finalChange = if (changeAmount > dustLimit) changeAmount else 0L
        
        // Vytvoř PSBT strukturu
        val psbtBase64 = buildPsbtBase64(
            utxos = utxos,
            outputs = outputs,
            changeAddress = if (finalChange > 0) changeAddress else null,
            changeAmount = finalChange,
            rbf = rbf
        )
        
        return PsbtBuildResult(
            psbtBase64 = psbtBase64,
            estimatedFee = fee,
            estimatedVsize = estimatedVsize,
            changeAmount = finalChange
        )
    }
    
    /**
     * Kombinuje více PSBT s částečnými podpisy.
     * Merge partial_sig entries ze všech PSBT do jednoho.
     */
    fun combinePsbts(psbts: List<String>): String {
        if (psbts.isEmpty()) {
            throw IllegalArgumentException("No PSBTs to combine")
        }
        if (psbts.size == 1) {
            return psbts.first()
        }
        
        log.info("Combining {} PSBTs", psbts.size)
        
        val parsedList = psbts.map { parsePsbt(Base64.getDecoder().decode(it)) }
        val base = parsedList.first()
        
        // Merge partial_sig entries from all PSBTs into the base
        val mergedInputKvs = base.inputKvs.mapIndexed { i, baseKvs ->
            val partialSigs = mutableMapOf<String, PsbtKV>() // key=hex(pubkey), value=KV
            val otherKvs = mutableListOf<PsbtKV>()
            
            // Collect existing entries from base
            for (kv in baseKvs) {
                if (kv.keyType == PSBT_IN_PARTIAL_SIG) {
                    partialSigs[bytesToHex(kv.keyData)] = kv
                } else {
                    otherKvs.add(kv)
                }
            }
            
            // Merge partial_sig from other PSBTs
            for (parsed in parsedList.drop(1)) {
                if (i < parsed.inputKvs.size) {
                    for (kv in parsed.inputKvs[i]) {
                        if (kv.keyType == PSBT_IN_PARTIAL_SIG) {
                            partialSigs[bytesToHex(kv.keyData)] = kv
                        }
                    }
                }
            }
            
            otherKvs + partialSigs.values.toList()
        }
        
        val merged = base.copy(inputKvs = mergedInputKvs)
        return Base64.getEncoder().encodeToString(serializePsbt(merged))
    }
    
    /**
     * Finalizuje PSBT - převede na raw transakci připravenou k broadcastu.
     * 
     * Parsuje PSBT binární formát (BIP-174), extrahuje partial_sig
     * z každého vstupu a sestaví finální segwit transakci s witness daty.
     */
    fun finalizePsbt(psbtBase64: String): FinalizeResult {
        try {
            val parsed = parsePsbt(Base64.getDecoder().decode(psbtBase64))
            
            // Check all inputs have at least one signature
            for ((i, inputKvs) in parsed.inputKvs.withIndex()) {
                val sigs = inputKvs.filter { it.keyType == PSBT_IN_PARTIAL_SIG }
                if (sigs.isEmpty()) {
                    log.warn("Input {} has no signatures, cannot finalize", i)
                    return FinalizeResult(txHex = "", txid = "", complete = false)
                }
            }
            
            // Build witness data for each input
            val witnesses = parsed.inputKvs.map { inputKvs -> buildWitness(inputKvs) }
            
            // Build final segwit transaction
            val finalTx = buildSegwitTransaction(parsed.unsignedTx, witnesses)
            val txHex = bytesToHex(finalTx)
            
            // Compute txid = double SHA-256 of NON-witness serialization, reversed
            val txidBytes = doubleSha256(parsed.unsignedTx)
            val txid = bytesToHex(txidBytes.reversedArray())
            
            log.info("Finalized PSBT: txid={}", txid)
            return FinalizeResult(txHex = txHex, txid = txid, complete = true)
        } catch (e: Exception) {
            log.error("Failed to finalize PSBT", e)
            return FinalizeResult(txHex = "", txid = "", complete = false)
        }
    }
    
    /**
     * Zkontroluje, kolik podpisů má PSBT.
     */
    fun analyzePsbt(psbtBase64: String): PsbtAnalysis {
        return try {
            val parsed = parsePsbt(Base64.getDecoder().decode(psbtBase64))
            
            // Count minimum signatures across all inputs
            var minSigCount = Int.MAX_VALUE
            for (inputKvs in parsed.inputKvs) {
                val sigCount = inputKvs.count { it.keyType == PSBT_IN_PARTIAL_SIG }
                if (sigCount < minSigCount) minSigCount = sigCount
            }
            if (parsed.inputKvs.isEmpty()) minSigCount = 0
            if (minSigCount == Int.MAX_VALUE) minSigCount = 0
            
            PsbtAnalysis(
                isComplete = minSigCount > 0,
                signatureCount = minSigCount,
                requiredSignatures = 1, // Cannot determine from PSBT alone
                missingSignatures = emptyList()
            )
        } catch (e: Exception) {
            log.error("Failed to analyze PSBT", e)
            PsbtAnalysis(
                isComplete = false,
                signatureCount = 0,
                requiredSignatures = 1,
                missingSignatures = listOf()
            )
        }
    }
    
    // ========== Helpers ==========
    
    private fun estimateVsize(
        inputCount: Int,
        outputCount: Int,
        isMultisig: Boolean,
        m: Int,
        n: Int
    ): Int {
        val overhead = 10
        val outputSize = outputCount * 31
        val inputSize = if (isMultisig) {
            inputCount * (57 + 73 * m + 34 * n)
        } else {
            inputCount * 68
        }
        return overhead + outputSize + inputSize
    }
    
    private fun buildPsbtBase64(
        utxos: List<SelectedUtxo>,
        outputs: List<TxOutput>,
        changeAddress: String?,
        changeAmount: Long,
        rbf: Boolean
    ): String {
        val psbt = mutableListOf<Byte>()
        
        // Magic: "psbt" + 0xff
        psbt.addAll(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()).toList())
        
        // Global: unsigned tx
        val unsignedTx = buildUnsignedTx(utxos, outputs, changeAddress, changeAmount, rbf)
        psbt.add(0x01)
        psbt.add(0x00)
        psbt.addAll(writeVarInt(unsignedTx.size.toLong()))
        psbt.addAll(unsignedTx.toList())
        psbt.add(0x00)
        
        // Input sections
        for (utxo in utxos) {
            val witnessUtxo = buildWitnessUtxo(utxo.value, utxo.scriptPubKey)
            psbt.add(0x01)
            psbt.add(0x01)
            psbt.addAll(writeVarInt(witnessUtxo.size.toLong()))
            psbt.addAll(witnessUtxo.toList())
            psbt.add(0x00)
        }
        
        // Output sections
        val totalOutputs = outputs.size + (if (changeAddress != null) 1 else 0)
        repeat(totalOutputs) { psbt.add(0x00) }
        
        return Base64.getEncoder().encodeToString(psbt.toByteArray())
    }
    
    private fun buildUnsignedTx(
        utxos: List<SelectedUtxo>,
        outputs: List<TxOutput>,
        changeAddress: String?,
        changeAmount: Long,
        rbf: Boolean
    ): ByteArray {
        val tx = mutableListOf<Byte>()
        
        // Version 2
        tx.addAll(intToLE(2, 4))
        
        // Inputs
        tx.addAll(writeVarInt(utxos.size.toLong()))
        for (utxo in utxos) {
            tx.addAll(hexToBytes(utxo.txid).reversed())
            tx.addAll(intToLE(utxo.vout, 4))
            tx.add(0x00)
            val seq: Int = if (rbf) 0xfffffffd.toInt() else 0xffffffff.toInt()
            tx.addAll(intToLE(seq, 4))
        }
        
        // Outputs
        val outputCount = outputs.size + (if (changeAddress != null) 1 else 0)
        tx.addAll(writeVarInt(outputCount.toLong()))
        
        for (output in outputs) {
            tx.addAll(longToLE(output.amountSats))
            val script = addressToScript(output.address)
            tx.addAll(writeVarInt(script.size.toLong()))
            tx.addAll(script.toList())
        }
        
        if (changeAddress != null && changeAmount > 0) {
            tx.addAll(longToLE(changeAmount))
            val script = addressToScript(changeAddress)
            tx.addAll(writeVarInt(script.size.toLong()))
            tx.addAll(script.toList())
        }
        
        // Locktime
        tx.addAll(intToLE(0, 4))
        
        return tx.toByteArray()
    }
    
    private fun buildWitnessUtxo(value: Long, scriptPubKeyHex: String?): ByteArray {
        val result = mutableListOf<Byte>()
        result.addAll(longToLE(value))
        val scriptBytes = scriptPubKeyHex?.let { hexToBytes(it) } ?: ByteArray(0)
        result.addAll(writeVarInt(scriptBytes.size.toLong()))
        result.addAll(scriptBytes.toList())
        return result.toByteArray()
    }
    
    private fun addressToScript(address: String): ByteArray {
        return when {
            address.startsWith("bc1q") || address.startsWith("tb1q") -> {
                val decoded = bech32Decode(address)
                byteArrayOf(0x00, 0x14) + decoded
            }
            address.startsWith("bc1p") || address.startsWith("tb1p") -> {
                val decoded = bech32Decode(address)
                byteArrayOf(0x51, 0x20) + decoded
            }
            else -> {
                log.warn("Unknown address format: {}", address)
                ByteArray(0)
            }
        }
    }
    
    private fun writeVarInt(value: Long): List<Byte> = when {
        value < 0xfd -> listOf(value.toByte())
        value <= 0xffff -> listOf(0xfd.toByte(), (value and 0xff).toByte(), ((value shr 8) and 0xff).toByte())
        else -> listOf(0xfe.toByte()) + intToLE(value.toInt(), 4)
    }
    
    private fun intToLE(value: Int, bytes: Int) = (0 until bytes).map { ((value shr (it * 8)) and 0xff).toByte() }
    
    private fun longToLE(value: Long) = (0 until 8).map { ((value shr (it * 8)) and 0xff).toByte() }
    
    private fun hexToBytes(hex: String): ByteArray {
        val data = ByteArray(hex.length / 2)
        for (i in data.indices) {
            data[i] = ((Character.digit(hex[2 * i], 16) shl 4) + Character.digit(hex[2 * i + 1], 16)).toByte()
        }
        return data
    }
    
    private fun bech32Decode(address: String): ByteArray {
        val charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        val hrpEnd = address.lastIndexOf('1')
        val data = address.substring(hrpEnd + 1).dropLast(6).drop(1)
        val values = data.map { charset.indexOf(it) }
        return convertBits(values, 5, 8)
    }
    
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
    
    // ========== PSBT Parsing & Serialization (BIP-174) ==========
    
    private const val PSBT_IN_WITNESS_UTXO = 0x01
    private const val PSBT_IN_PARTIAL_SIG = 0x02
    private const val PSBT_IN_WITNESS_SCRIPT = 0x05
    private const val PSBT_GLOBAL_UNSIGNED_TX = 0x00
    
    private data class PsbtKV(
        val keyType: Int,
        val keyData: ByteArray,
        val value: ByteArray
    )
    
    private data class ParsedPsbt(
        val unsignedTx: ByteArray,
        val globalKvs: List<PsbtKV>,
        val inputKvs: List<List<PsbtKV>>,
        val outputKvs: List<List<PsbtKV>>
    )
    
    /**
     * Parsuje PSBT binární formát podle BIP-174.
     */
    private fun parsePsbt(data: ByteArray): ParsedPsbt {
        // Verify magic: "psbt" + 0xff
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
            if (keyLen == 0L) break // separator
            
            val keyBytes = data.sliceArray(pos until (pos + keyLen.toInt()))
            pos += keyLen.toInt()
            
            val (valLen, p2) = readCompactSize(data, pos)
            pos = p2
            val valBytes = data.sliceArray(pos until (pos + valLen.toInt()))
            pos += valLen.toInt()
            
            val keyType = keyBytes[0].toInt() and 0xFF
            val keyData = if (keyBytes.size > 1) keyBytes.sliceArray(1 until keyBytes.size) else ByteArray(0)
            
            globalKvs.add(PsbtKV(keyType, keyData, valBytes))
            
            if (keyType == PSBT_GLOBAL_UNSIGNED_TX) {
                unsignedTx = valBytes
            }
        }
        
        require(unsignedTx.isNotEmpty()) { "PSBT missing unsigned transaction" }
        
        val inputCount = countTxInputs(unsignedTx)
        val outputCount = countTxOutputs(unsignedTx)
        
        // Parse input sections
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
        
        // Parse output sections
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
    
    /**
     * Serializuje ParsedPsbt zpět do binárního formátu.
     */
    private fun serializePsbt(parsed: ParsedPsbt): ByteArray {
        val result = mutableListOf<Byte>()
        
        // Magic
        result.addAll(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()).toList())
        
        // Global key-value pairs
        for (kv in parsed.globalKvs) {
            val key = byteArrayOf(kv.keyType.toByte()) + kv.keyData
            result.addAll(writeVarInt(key.size.toLong()))
            result.addAll(key.toList())
            result.addAll(writeVarInt(kv.value.size.toLong()))
            result.addAll(kv.value.toList())
        }
        result.add(0x00) // separator
        
        // Input sections
        for (inputKvs in parsed.inputKvs) {
            for (kv in inputKvs) {
                val key = byteArrayOf(kv.keyType.toByte()) + kv.keyData
                result.addAll(writeVarInt(key.size.toLong()))
                result.addAll(key.toList())
                result.addAll(writeVarInt(kv.value.size.toLong()))
                result.addAll(kv.value.toList())
            }
            result.add(0x00) // separator
        }
        
        // Output sections
        for (outputKvs in parsed.outputKvs) {
            for (kv in outputKvs) {
                val key = byteArrayOf(kv.keyType.toByte()) + kv.keyData
                result.addAll(writeVarInt(key.size.toLong()))
                result.addAll(key.toList())
                result.addAll(writeVarInt(kv.value.size.toLong()))
                result.addAll(kv.value.toList())
            }
            result.add(0x00) // separator
        }
        
        return result.toByteArray()
    }
    
    /**
     * Sestaví witness data pro jeden vstup z partial_sig entries.
     * P2WPKH: [signature, pubkey]
     * P2WSH multisig: [OP_0, sig1, ..., sigM, witnessScript]
     */
    private fun buildWitness(inputKvs: List<PsbtKV>): List<ByteArray> {
        val partialSigs = inputKvs.filter { it.keyType == PSBT_IN_PARTIAL_SIG }
        val witnessScript = inputKvs.firstOrNull { it.keyType == PSBT_IN_WITNESS_SCRIPT }
        
        if (witnessScript != null) {
            // P2WSH multisig: OP_0 + signatures + witnessScript
            val items = mutableListOf<ByteArray>()
            items.add(ByteArray(0)) // OP_0 for CHECKMULTISIG bug
            for (sig in partialSigs) {
                items.add(sig.value)
            }
            items.add(witnessScript.value)
            return items
        } else {
            // P2WPKH: [signature, pubkey]
            val sig = partialSigs.firstOrNull()
            return if (sig != null) {
                listOf(sig.value, sig.keyData) // keyData = compressed pubkey
            } else {
                emptyList()
            }
        }
    }
    
    /**
     * Sestaví finální segwit transakci z unsigned tx + witness dat.
     * Formát: [version][marker=0x00][flag=0x01][inputs][outputs][witness...][locktime]
     */
    private fun buildSegwitTransaction(unsignedTx: ByteArray, witnesses: List<List<ByteArray>>): ByteArray {
        val result = mutableListOf<Byte>()
        
        // Version (first 4 bytes)
        result.addAll(unsignedTx.slice(0 until 4))
        
        // Segwit marker + flag
        result.add(0x00)
        result.add(0x01)
        
        // Inputs and outputs (everything between version and locktime)
        val txBody = unsignedTx.slice(4 until unsignedTx.size - 4)
        result.addAll(txBody)
        
        // Witness data for each input
        for (witness in witnesses) {
            result.addAll(writeVarInt(witness.size.toLong()))
            for (item in witness) {
                result.addAll(writeVarInt(item.size.toLong()))
                result.addAll(item.toList())
            }
        }
        
        // Locktime (last 4 bytes)
        result.addAll(unsignedTx.slice(unsignedTx.size - 4 until unsignedTx.size))
        
        return result.toByteArray()
    }
    
    /**
     * Přečte compact size (variable-length integer) z byte pole.
     */
    private fun readCompactSize(data: ByteArray, offset: Int): Pair<Long, Int> {
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
    
    /**
     * Spočítá vstupy z unsigned transaction.
     */
    private fun countTxInputs(tx: ByteArray): Int {
        val (count, _) = readCompactSize(tx, 4) // skip version (4 bytes)
        return count.toInt()
    }
    
    /**
     * Spočítá výstupy z unsigned transaction.
     */
    private fun countTxOutputs(tx: ByteArray): Int {
        var pos = 4 // skip version
        val (inputCount, p1) = readCompactSize(tx, pos)
        pos = p1
        
        // Skip all inputs
        for (i in 0 until inputCount.toInt()) {
            pos += 32 + 4 // prevout hash (32) + index (4)
            val (scriptLen, p2) = readCompactSize(tx, pos)
            pos = p2
            pos += scriptLen.toInt() // scriptSig
            pos += 4 // sequence
        }
        
        val (outputCount, _) = readCompactSize(tx, pos)
        return outputCount.toInt()
    }
    
    private fun doubleSha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(data))
    }
    
    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

data class SelectedUtxo(
    val txid: String,
    val vout: Int,
    val value: Long,
    val scriptPubKey: String?,
    val derivationPath: String? = null
)

data class PsbtBuildResult(
    val psbtBase64: String,
    val estimatedFee: Long,
    val estimatedVsize: Int,
    val changeAmount: Long
)

data class FinalizeResult(
    val txHex: String,
    val txid: String,
    val complete: Boolean
)

data class PsbtAnalysis(
    val isComplete: Boolean,
    val signatureCount: Int,
    val requiredSignatures: Int,
    val missingSignatures: List<String>
)
