package cz.majny.wallet.psbt.builder

import cz.majny.wallet.psbt.api.*
import cz.majny.wallet.psbt.builder.PsbtEncoding.PSBT_IN_BIP32_DERIVATION
import cz.majny.wallet.psbt.builder.PsbtEncoding.PSBT_IN_NON_WITNESS_UTXO
import cz.majny.wallet.psbt.builder.PsbtEncoding.PSBT_IN_WITNESS_SCRIPT
import cz.majny.wallet.psbt.builder.PsbtEncoding.PSBT_IN_WITNESS_UTXO
import cz.majny.wallet.psbt.builder.PsbtEncoding.PSBT_OUT_BIP32_DERIVATION
import org.bitcoinj.base.BitcoinNetwork
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.slf4j.LoggerFactory
import java.util.*

/*
 * Core PSBT (BIP-174) builder. Creates unsigned Bitcoin transactions in PSBT format
 * with all required metadata (witness UTXOs, BIP-32 derivation paths, witness scripts).
 * Supports singlesig P2WPKH and multisig P2WSH transactions.
 *
 * Low-level encoding is delegated to PsbtEncoding.
 * Trezor Connect params are built by TrezorParamsBuilder.
 */
object PsbtBuilder {

    private val log = LoggerFactory.getLogger(PsbtBuilder::class.java)

    /*
     * Creates a complete PSBT for the given wallet, UTXOs, and outputs.
     * Calculates fee from estimated vsize, checks dust limit on change,
     * and returns the PSBT as base64 along with fee and change metadata.
     */
    fun createPsbt(
        wallet: WalletDetailDto,
        utxos: List<SelectedUtxo>,
        outputs: List<TxOutput>,
        changeAddress: String,
        changeIndex: Int = 0,
        feeRate: Double,
        rbf: Boolean = true
    ): PsbtBuildResult {
        log.info("Building PSBT: {} inputs, {} outputs, fee rate {} sats/vB",
            utxos.size, outputs.size, feeRate)

        val totalInput = utxos.sumOf { it.value }
        val totalOutput = outputs.sumOf { it.amountSats }

        val estimatedVsize = estimateVsize(
            inputCount = utxos.size,
            outputCount = outputs.size + 1, // +1 for change
            isMultisig = wallet.type == "MULTI_SIG",
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

        // Dust limit check — change below threshold is added to the fee instead
        val dustLimit = 546L
        val finalChange = if (changeAmount > dustLimit) {
            changeAmount
        } else {
            if (changeAmount > 0) {
                log.info("Change amount {} sats is below dust limit ({}), added to fee", changeAmount, dustLimit)
            }
            0L
        }

        val psbtBase64 = buildPsbtBase64(
            wallet = wallet,
            utxos = utxos,
            outputs = outputs,
            changeAddress = if (finalChange > 0) changeAddress else null,
            changeAmount = finalChange,
            changeIndex = if (finalChange > 0) changeIndex else null,
            rbf = rbf
        )

        return PsbtBuildResult(
            psbtBase64 = psbtBase64,
            estimatedFee = fee,
            estimatedVsize = estimatedVsize,
            changeAmount = finalChange
        )
    }

    /*
     * Estimates transaction virtual size in vBytes.
     * vsize = weight / 4, where weight = non_witness_bytes * 4 + witness_bytes * 1 (BIP-141).
     *
     * Overhead (10 vB):
     *   version(4B) + locktime(4B) + segwit marker+flag(~0.5B) + input/output count varints(~1.5B)
     *
     * Each output (31 vB):
     *   amount(8B) + script_length(1B) + scriptPubKey(22B P2WPKH / 34B P2WSH) ≈ 31B
     *
     * Singlesig P2WPKH input (68 vB):
     *   Non-witness: prevout_hash(32B) + prevout_index(4B) + empty_scriptSig(1B) + sequence(4B) = 41B * 4 = 164 wu
     *   Witness: witness_count(1B) + sig_len(1B) + DER_signature(72B) + pubkey_len(1B) + compressed_pubkey(33B) = 108 wu
     *   Total: (164 + 108) / 4 = 68 vB
     *
     * Multisig P2WSH input:
     *   Non-witness: same 41B * 4 = 164 wu
     *   Witness: OP_0(1B) + M * (push + DER_sig ≈ 73B) + script_push(1B) + witness_script(3 + 34*N)
     *   Witness bytes = 1 + 73*M + 1 + 3 + 34*N = 5 + 73*M + 34*N
     *   vsize = (164 + 5 + 73*M + 34*N) / 4 = 41 + (5 + 73*M + 34*N) / 4
     *   Example 2-of-3: 41 + (5 + 146 + 102)/4 = 41 + 63 = 104 vB per input
     */
    fun estimateVsize(
        inputCount: Int,
        outputCount: Int,
        isMultisig: Boolean,
        m: Int,
        n: Int
    ): Int {
        val overhead = 10
        val outputSize = outputCount * 31
        val inputSize = if (isMultisig) {
            // vsize = (non_witness_weight + witness_bytes) / 4
            // non_witness = 41 bytes = 164 WU, witness = 5 + 73*M + 34*N bytes (1 WU each)
            inputCount * (41 + (5 + 73 * m + 34 * n) / 4)
        } else {
            inputCount * 68
        }
        return overhead + outputSize + inputSize
    }

    // ========== Internal PSBT Construction ==========

    /*
     * Builds the full PSBT binary structure and returns it as base64.
     * Structure: magic || global section || per-input sections || per-output sections.
     * Includes PSBT_IN_NON_WITNESS_UTXO, PSBT_IN_WITNESS_UTXO, PSBT_IN_WITNESS_SCRIPT (multisig),
     * PSBT_IN_BIP32_DERIVATION, and PSBT_OUT_BIP32_DERIVATION (change output).
     */
    private fun buildPsbtBase64(
        wallet: WalletDetailDto,
        utxos: List<SelectedUtxo>,
        outputs: List<TxOutput>,
        changeAddress: String?,
        changeAmount: Long,
        changeIndex: Int? = null,
        rbf: Boolean
    ): String {
        log.debug("=== PSBT BUILD START ===")
        log.debug("wallet.type={} wallet.network={} wallet.m={} wallet.n={}",
            wallet.type, wallet.network, wallet.m, wallet.n)
        log.debug("cosigners ({}):", wallet.cosigners.size)
        for (cos in wallet.cosigners) {
            log.debug("  cosigner idx={} fingerprint={} originPath={} xpubRoot={}...",
                cos.idx, cos.fingerprint, cos.originPath, cos.xpubRoot.take(24))
        }
        log.debug("utxos ({}):", utxos.size)
        for (u in utxos) {
            val rawInfo = if (u.rawTxHex != null) "${u.rawTxHex.length / 2} bytes" else "MISSING!"
            log.debug("  utxo {}:{} value={} scriptPubKey={} type={} idx={} addr={} rawTx={}",
                u.txid.take(16), u.vout, u.value, u.scriptPubKey, u.addressType, u.addressIndex, u.address, rawInfo)
        }
        log.debug("outputs ({}):", outputs.size)
        for (o in outputs) { log.debug("  output addr={} amount={}", o.address, o.amountSats) }
        log.debug("changeAddress={} changeAmount={} changeIndex={}", changeAddress, changeAmount, changeIndex)

        val psbt = mutableListOf<Byte>()

        // PSBT magic: "psbt" + 0xff separator
        psbt.addAll(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()).toList())

        // Global section: unsigned transaction
        val unsignedTx = buildUnsignedTx(utxos, outputs, changeAddress, changeAmount, rbf)
        psbt.add(0x01) // key length
        psbt.add(0x00) // key type = PSBT_GLOBAL_UNSIGNED_TX
        psbt.addAll(PsbtEncoding.writeVarInt(unsignedTx.size.toLong()))
        psbt.addAll(unsignedTx.toList())
        psbt.add(0x00) // global section separator

        val isMultisig = wallet.type == "MULTI_SIG" && wallet.cosigners.isNotEmpty()

        // Pre-derive cosigner chain keys once (both chains, all cosigners).
        // Needed for PSBT_IN_BIP32_DERIVATION so Trezor can identify the signing key.
        val cosignerChainKeys: Map<Int, List<Pair<CosignerDto, DeterministicKey>>>? =
            if (wallet.cosigners.isNotEmpty()) {
                val btcNetwork = if (wallet.network.lowercase() in listOf("mainnet", "bitcoin"))
                    BitcoinNetwork.MAINNET else BitcoinNetwork.TESTNET
                mapOf(
                    0 to wallet.cosigners.map { cos ->
                        val accountKey = DeterministicKey.deserializeB58(cos.xpubRoot, btcNetwork)
                        cos to HDKeyDerivation.deriveChildKey(accountKey, 0)
                    },
                    1 to wallet.cosigners.map { cos ->
                        val accountKey = DeterministicKey.deserializeB58(cos.xpubRoot, btcNetwork)
                        cos to HDKeyDerivation.deriveChildKey(accountKey, 1)
                    }
                )
            } else null

        // Per-input sections
        for (utxo in utxos) {
            // PSBT_IN_NON_WITNESS_UTXO (key 0x00) — full previous transaction.
            // Trezor firmware 2.4+ requires this for ALL inputs (even P2WPKH)
            // to verify output amounts and display the correct fee.
            if (utxo.rawTxHex != null) {
                PsbtEncoding.writeKv(psbt, PSBT_IN_NON_WITNESS_UTXO, ByteArray(0),
                    PsbtEncoding.hexToBytes(utxo.rawTxHex))
            }

            // PSBT_IN_WITNESS_UTXO (key 0x01) — amount + scriptPubKey of the spent output
            val witnessUtxo = PsbtEncoding.buildWitnessUtxo(utxo.value, utxo.scriptPubKey)
            psbt.add(0x01) // key length
            psbt.add(0x01) // key type = PSBT_IN_WITNESS_UTXO
            psbt.addAll(PsbtEncoding.writeVarInt(witnessUtxo.size.toLong()))
            psbt.addAll(witnessUtxo.toList())

            if (cosignerChainKeys != null
                && utxo.addressIndex != null && utxo.addressType != null
            ) {
                val chain = if (utxo.addressType == "change") 1 else 0
                val idx = utxo.addressIndex
                val m = wallet.m ?: 1
                val chainEntries = cosignerChainKeys[chain] ?: emptyList()

                // Derive child pubkeys for this input's address index
                val cosignerPubkeys = chainEntries.map { (cos, chainKey) ->
                    val childKey = HDKeyDerivation.deriveChildKey(chainKey, idx)
                    Triple(cos, childKey.pubKey, childKey)
                }

                if (isMultisig) {
                    // BIP-67: sort pubkeys lexicographically for sortedmulti
                    val sorted = if (wallet.receiveDescriptor.contains("sortedmulti(")) {
                        cosignerPubkeys.sortedWith(
                            compareBy<Triple<CosignerDto, ByteArray, DeterministicKey>> {
                                it.second.size
                            }.thenBy { PsbtEncoding.bytesToHex(it.second) }
                        )
                    } else {
                        cosignerPubkeys
                    }

                    // PSBT_IN_WITNESS_SCRIPT (key 0x05) — multisig redeem script
                    val witnessScript = PsbtEncoding.buildMultisigWitnessScript(m, sorted.map { it.second })
                    PsbtEncoding.writeKv(psbt, PSBT_IN_WITNESS_SCRIPT, ByteArray(0), witnessScript)
                }

                // PSBT_IN_BIP32_DERIVATION (key 0x06) for each cosigner
                for ((cos, pubkey, _) in cosignerPubkeys) {
                    val bip32Value = PsbtEncoding.encodeBip32Derivation(cos.fingerprint, cos.originPath, chain, idx)
                    log.debug("  BIP32 deriv: fp={} path={} chain={} idx={} pubkey={} value={}",
                        cos.fingerprint, cos.originPath, chain, idx,
                        PsbtEncoding.bytesToHex(pubkey), PsbtEncoding.bytesToHex(bip32Value))
                    PsbtEncoding.writeKv(psbt, PSBT_IN_BIP32_DERIVATION, pubkey, bip32Value)
                }
            }

            psbt.add(0x00) // input separator
        }

        // Per-output sections
        val totalOutputs = outputs.size + (if (changeAddress != null) 1 else 0)
        for (i in 0 until totalOutputs) {
            // PSBT_OUT_BIP32_DERIVATION for change output so Trezor identifies it as internal
            val isChangeOutput = changeAddress != null && i == outputs.size
            if (isChangeOutput && cosignerChainKeys != null && changeIndex != null) {
                val chainEntries = cosignerChainKeys[1] ?: emptyList()
                val changePubkeys = chainEntries.map { (cos, chainKey) ->
                    val childKey = HDKeyDerivation.deriveChildKey(chainKey, changeIndex)
                    Triple(cos, childKey.pubKey, childKey)
                }
                for ((cos, pubkey, _) in changePubkeys) {
                    val bip32Value = PsbtEncoding.encodeBip32Derivation(cos.fingerprint, cos.originPath, 1, changeIndex)
                    PsbtEncoding.writeKv(psbt, PSBT_OUT_BIP32_DERIVATION, pubkey, bip32Value)
                }
            }
            psbt.add(0x00) // output separator
        }

        val result = Base64.getEncoder().encodeToString(psbt.toByteArray())
        log.debug("=== PSBT BUILD END ({} bytes raw, {} chars base64) ===", psbt.size, result.length)
        log.info("PSBT base64: {}", result)

        // Self-check: parse the generated PSBT to catch structural issues early
        try {
            val parsed = PsbtEncoding.parsePsbt(Base64.getDecoder().decode(result))
            log.debug("PSBT self-check OK: {} inputs, {} outputs",
                parsed.inputKvs.size, parsed.outputKvs.size)
            for ((inputIdx, inputKvs) in parsed.inputKvs.withIndex()) {
                val hasNonWit = inputKvs.any { it.keyType == PSBT_IN_NON_WITNESS_UTXO }
                val hasWitUtxo = inputKvs.any { it.keyType == PSBT_IN_WITNESS_UTXO }
                val hasBip32 = inputKvs.any { it.keyType == PSBT_IN_BIP32_DERIVATION }
                log.debug("  input[{}]: NON_WIT={} WIT_UTXO={} BIP32_DERIV={}",
                    inputIdx, hasNonWit, hasWitUtxo, hasBip32)
            }
        } catch (e: Exception) {
            log.error("PSBT self-check FAILED: {}", e.message)
        }

        return result
    }

    /*
     * Builds the unsigned Bitcoin transaction (without witness data).
     * Format: version || inputs (prevout + empty scriptSig + sequence) || outputs (amount + scriptPubKey) || locktime.
     */
    private fun buildUnsignedTx(
        utxos: List<SelectedUtxo>,
        outputs: List<TxOutput>,
        changeAddress: String?,
        changeAmount: Long,
        rbf: Boolean
    ): ByteArray {
        val tx = mutableListOf<Byte>()

        // Version 2 (required for RBF / BIP-125)
        tx.addAll(PsbtEncoding.intToLE(2, 4))

        // Inputs
        tx.addAll(PsbtEncoding.writeVarInt(utxos.size.toLong()))
        for (utxo in utxos) {
            tx.addAll(PsbtEncoding.hexToBytes(utxo.txid).reversed()) // prevout hash (LE)
            tx.addAll(PsbtEncoding.intToLE(utxo.vout, 4))            // prevout index
            tx.add(0x00)                                              // empty scriptSig
            val seq: Int = if (rbf) 0xfffffffd.toInt() else 0xffffffff.toInt()
            tx.addAll(PsbtEncoding.intToLE(seq, 4))                   // sequence
        }

        // Outputs
        val outputCount = outputs.size + (if (changeAddress != null) 1 else 0)
        tx.addAll(PsbtEncoding.writeVarInt(outputCount.toLong()))

        for (output in outputs) {
            tx.addAll(PsbtEncoding.longToLE(output.amountSats))
            val script = PsbtEncoding.addressToScript(output.address)
            tx.addAll(PsbtEncoding.writeVarInt(script.size.toLong()))
            tx.addAll(script.toList())
        }

        if (changeAddress != null && changeAmount > 0) {
            tx.addAll(PsbtEncoding.longToLE(changeAmount))
            val script = PsbtEncoding.addressToScript(changeAddress)
            tx.addAll(PsbtEncoding.writeVarInt(script.size.toLong()))
            tx.addAll(script.toList())
        }

        // Locktime
        tx.addAll(PsbtEncoding.intToLE(0, 4))

        return tx.toByteArray()
    }
}

// ========== Data Classes ==========

data class SelectedUtxo(
    val txid: String,
    val vout: Int,
    val value: Long,
    val scriptPubKey: String?,
    val addressIndex: Int? = null,   // BIP-32 child index within chain
    val addressType: String? = null, // "receive" (chain 0) or "change" (chain 1)
    val address: String? = null,     // the address owning this UTXO
    val rawTxHex: String? = null     // raw previous tx hex for PSBT_IN_NON_WITNESS_UTXO
)

data class PsbtBuildResult(
    val psbtBase64: String,
    val estimatedFee: Long,
    val estimatedVsize: Int,
    val changeAmount: Long
)
