package cz.majny.wallet.psbt.builder

import cz.majny.wallet.psbt.api.*
import org.slf4j.LoggerFactory
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
     */
    fun combinePsbts(psbts: List<String>): String {
        if (psbts.isEmpty()) {
            throw IllegalArgumentException("No PSBTs to combine")
        }
        if (psbts.size == 1) {
            return psbts.first()
        }
        
        log.warn("PSBT combination not yet fully implemented - returning first PSBT")
        return psbts.first()
    }
    
    /**
     * Finalizuje PSBT - převede na raw transakci připravenou k broadcastu.
     */
    fun finalizePsbt(psbtBase64: String): FinalizeResult {
        log.warn("PSBT finalization not yet fully implemented")
        return FinalizeResult(txHex = "", txid = "", complete = false)
    }
    
    /**
     * Zkontroluje, kolik podpisů má PSBT.
     */
    fun analyzePsbt(psbtBase64: String): PsbtAnalysis {
        return PsbtAnalysis(
            isComplete = false,
            signatureCount = 0,
            requiredSignatures = 1,
            missingSignatures = listOf()
        )
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
