package cz.majny.wallet.psbt.builder

import cz.majny.wallet.psbt.api.*
import org.bitcoinj.base.BitcoinNetwork
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
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
        changeIndex: Int = 0,
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
        
        // Dust limit check — pokud je change pod limitem, přidá se do fee
        val dustLimit = 546L
        val finalChange = if (changeAmount > dustLimit) {
            changeAmount
        } else {
            if (changeAmount > 0) {
                log.info("Change amount {} sats je pod dust limitem ({}), přidáno do fee", changeAmount, dustLimit)
            }
            0L
        }
        
        // Vytvoř PSBT strukturu
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
     * Pokud PSBT obsahuje witness script (multisig), extrahuje M z něj.
     */
    fun analyzePsbt(psbtBase64: String): PsbtAnalysis {
        return try {
            val parsed = parsePsbt(Base64.getDecoder().decode(psbtBase64))
            
            // Determine required signatures from witness script (if present)
            var requiredSigs = 1
            for (inputKvs in parsed.inputKvs) {
                val ws = inputKvs.firstOrNull { it.keyType == PSBT_IN_WITNESS_SCRIPT }
                if (ws != null && ws.value.isNotEmpty()) {
                    // First byte of witness script is OP_M: OP_1=0x51 → M=1, OP_2=0x52 → M=2, ...
                    val opM = ws.value[0].toInt() and 0xFF
                    if (opM in 0x51..0x60) {
                        requiredSigs = opM - 0x50
                    }
                    break // all inputs share the same M
                }
            }

            // Count minimum signatures across all inputs
            var minSigCount = Int.MAX_VALUE
            for (inputKvs in parsed.inputKvs) {
                val sigCount = inputKvs.count { it.keyType == PSBT_IN_PARTIAL_SIG }
                if (sigCount < minSigCount) minSigCount = sigCount
            }
            if (parsed.inputKvs.isEmpty()) minSigCount = 0
            if (minSigCount == Int.MAX_VALUE) minSigCount = 0

            // Identify which cosigner fingerprints have NOT yet signed
            val allFingerprints = mutableSetOf<String>()
            val signedFingerprints = mutableSetOf<String>()
            for (inputKvs in parsed.inputKvs) {
                // Collect all known cosigner fingerprints from BIP32 derivation entries
                for (kv in inputKvs.filter { it.keyType == PSBT_IN_BIP32_DERIVATION }) {
                    if (kv.value.size >= 4) {
                        allFingerprints.add(bytesToHex(kv.value.sliceArray(0 until 4)))
                    }
                }
                // Collect pubkeys that have signed — match against BIP32 derivation to get fingerprint
                val sigPubkeys = inputKvs.filter { it.keyType == PSBT_IN_PARTIAL_SIG }
                    .map { bytesToHex(it.keyData) }.toSet()
                for (kv in inputKvs.filter { it.keyType == PSBT_IN_BIP32_DERIVATION }) {
                    val pubkeyHex = bytesToHex(kv.keyData)
                    if (pubkeyHex in sigPubkeys && kv.value.size >= 4) {
                        signedFingerprints.add(bytesToHex(kv.value.sliceArray(0 until 4)))
                    }
                }
            }
            val missing = (allFingerprints - signedFingerprints).toList()
            
            PsbtAnalysis(
                isComplete = minSigCount >= requiredSigs,
                signatureCount = minSigCount,
                requiredSignatures = requiredSigs,
                missingSignatures = missing
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
    
    /**
     * Převede Bitcoin adresu na scriptPubKey hex string.
     * Používá se pro PSBT_IN_WITNESS_UTXO pole.
     */
    fun addressToScriptHex(address: String): String = bytesToHex(addressToScript(address))

    /**
     * Sestaví Trezor Connect signTransaction parametry ze stejných dat jako PSBT.
     * Podporuje singlesig P2WPKH i multisig P2WSH.
     *
     * @param signerCosignerIndex Pro multisig: index cosignera ktery bude podepisovat (default 0).
     */
    fun buildTrezorConnectParams(
        wallet: WalletDetailDto,
        utxos: List<SelectedUtxo>,
        outputs: List<TxOutput>,
        changeAddress: String?,
        changeAmount: Long,
        changeIndex: Int?,
        signerCosignerIndex: Int = 0
    ): TrezorConnectParams? {
        val coin = if (wallet.network.lowercase() in listOf("mainnet", "bitcoin")) "Bitcoin" else "Testnet"

        val refTxs = utxos.mapNotNull { utxo ->
            val hex = utxo.rawTxHex ?: return@mapNotNull null
            TrezorConnectRefTx(hash = utxo.txid, tx_hex = hex)
        }.distinctBy { it.hash }.ifEmpty { null }

        if (refTxs == null) {
            log.warn("No raw tx hex available for refTxs — Trezor may fail to verify inputs")
        } else {
            log.info("Built {} refTxs for Trezor Connect", refTxs.size)
        }

        if (wallet.type == "MULTI_SIG") {
            return buildMultisigTrezorConnectParams(
                wallet, utxos, outputs, changeAddress, changeAmount, changeIndex, coin, refTxs, signerCosignerIndex
            )
        }

        // ---- Singlesig P2WPKH ----
        val originPath: List<Long> = if (wallet.cosigners.isNotEmpty()) {
            parseOriginPathToUint32(wallet.cosigners.first().originPath)
        } else {
            val parsed = parseDescriptorOrigin(wallet.receiveDescriptor)
            if (parsed == null) {
                log.warn("Cannot parse origin path from descriptor: {}", wallet.receiveDescriptor)
                return null
            }
            parsed
        }

        val trezorInputs = utxos.map { utxo ->
            val chain = if (utxo.addressType == "change") 1L else 0L
            val idx = (utxo.addressIndex ?: 0).toLong()
            TrezorConnectInput(
                address_n = originPath + listOf(chain, idx),
                prev_hash = utxo.txid,
                prev_index = utxo.vout,
                amount = utxo.value.toString(),
                script_type = "SPENDWITNESS"
            )
        }

        val trezorOutputs = mutableListOf<TrezorConnectOutput>()
        for (output in outputs) {
            trezorOutputs.add(TrezorConnectOutput(
                address = output.address,
                amount = output.amountSats.toString(),
                script_type = "PAYTOADDRESS"
            ))
        }

        if (changeAddress != null && changeAmount > 0 && changeIndex != null) {
            trezorOutputs.add(TrezorConnectOutput(
                address_n = originPath + listOf(1L, changeIndex.toLong()),
                amount = changeAmount.toString(),
                script_type = "PAYTOWITNESS"
            ))
        }

        return TrezorConnectParams(
            coin = coin,
            inputs = trezorInputs,
            outputs = trezorOutputs,
            refTxs = refTxs
        )
    }

    /**
     * Sestaví Trezor Connect params pro multisig P2WSH transakci.
     * Každý input/output obsahuje `multisig` objekt s pubkeys všech cosignerů.
     * address_n = derivation path cosignera ktery podepisuje (signerCosignerIndex).
     */
    private fun buildMultisigTrezorConnectParams(
        wallet: WalletDetailDto,
        utxos: List<SelectedUtxo>,
        outputs: List<TxOutput>,
        changeAddress: String?,
        changeAmount: Long,
        changeIndex: Int?,
        coin: String,
        refTxs: List<TrezorConnectRefTx>?,
        signerCosignerIndex: Int
    ): TrezorConnectParams? {
        if (wallet.cosigners.isEmpty()) {
            log.warn("Multisig wallet has no cosigners, cannot build TrezorConnectParams")
            return null
        }

        val m = wallet.m ?: 2
        val sortedCosigners = wallet.cosigners.sortedBy { it.idx }
        val signerCosigner = sortedCosigners.getOrNull(signerCosignerIndex) ?: run {
            log.warn("Cosigner index {} out of range ({})", signerCosignerIndex, sortedCosigners.size)
            return null
        }
        val signerOriginPath = parseOriginPathToUint32(signerCosigner.originPath)

        log.info("Building multisig TrezorConnectParams: m={} n={} signer=cosigner[{}] fp={} path={}",
            m, sortedCosigners.size, signerCosignerIndex, signerCosigner.fingerprint, signerCosigner.originPath)

        val btcNetwork = if (coin == "Bitcoin") BitcoinNetwork.MAINNET else BitcoinNetwork.TESTNET

        // Convert each cosigner's xpub to HDNodeDto once
        val cosignerHdNodes = sortedCosigners.map { cos ->
            xpubToHDNode(cos.xpubRoot, btcNetwork)
        }

        val isSortedMulti = wallet.receiveDescriptor.contains("sortedmulti(")

        // BIP-67: sort cosigner HDNodes by derived child pubkey at a given chain/index.
        // Trezor firmware does NOT sort pubkeys internally — the order we provide must
        // exactly match the witness script order, otherwise Trezor computes a different
        // P2WSH address and returns Failure_DataError.
        fun sortedMultisigPubkeys(chain: Int, index: Int): List<TrezorConnectMultisigPubkey> {
            if (!isSortedMulti) {
                return cosignerHdNodes.map { hdNode ->
                    TrezorConnectMultisigPubkey(node = hdNode, address_n = listOf(chain.toLong(), index.toLong()))
                }
            }
            // Derive child pubkeys and sort by BIP-67 (lexicographic on compressed pubkey)
            val withDerived = sortedCosigners.mapIndexed { i, cos ->
                val accountKey = DeterministicKey.deserializeB58(cos.xpubRoot, btcNetwork)
                val chainKey = HDKeyDerivation.deriveChildKey(accountKey, chain)
                val childKey = HDKeyDerivation.deriveChildKey(chainKey, index)
                Pair(cosignerHdNodes[i], childKey.pubKey)
            }
            val sorted = withDerived.sortedWith(
                compareBy<Pair<HDNodeDto, ByteArray>> { it.second.size }
                    .thenBy { it.second.joinToString("") { b -> "%02x".format(b) } }
            )
            return sorted.map { (hdNode, _) ->
                TrezorConnectMultisigPubkey(node = hdNode, address_n = listOf(chain.toLong(), index.toLong()))
            }
        }

        val trezorInputs = utxos.map { utxo ->
            val chain = if (utxo.addressType == "change") 1 else 0
            val idx = utxo.addressIndex ?: 0

            TrezorConnectInput(
                address_n = signerOriginPath + listOf(chain.toLong(), idx.toLong()),
                prev_hash = utxo.txid,
                prev_index = utxo.vout,
                amount = utxo.value.toString(),
                script_type = "SPENDWITNESS",
                multisig = TrezorConnectMultisig(
                    pubkeys = sortedMultisigPubkeys(chain, idx),
                    m = m,
                    signatures = sortedCosigners.map { "" }
                )
            )
        }

        val trezorOutputs = mutableListOf<TrezorConnectOutput>()
        for (output in outputs) {
            trezorOutputs.add(TrezorConnectOutput(
                address = output.address,
                amount = output.amountSats.toString(),
                script_type = "PAYTOADDRESS"
            ))
        }

        if (changeAddress != null && changeAmount > 0 && changeIndex != null) {
            trezorOutputs.add(TrezorConnectOutput(
                address_n = signerOriginPath + listOf(1L, changeIndex.toLong()),
                amount = changeAmount.toString(),
                script_type = "PAYTOWITNESS",
                multisig = TrezorConnectMultisig(
                    pubkeys = sortedMultisigPubkeys(1, changeIndex),
                    m = m,
                    signatures = sortedCosigners.map { "" }
                )
            ))
        }

        return TrezorConnectParams(
            coin = coin,
            inputs = trezorInputs,
            outputs = trezorOutputs,
            refTxs = refTxs
        )
    }

    /**
     * Parsuje origin path "84h/1h/0h" nebo "84'/1'/0'" na List<Long> (uint32).
     * Hardened indexy mají bit 0x80000000.
     */
    private fun parseOriginPathToUint32(originPath: String): List<Long> {
        return originPath.split("/").filter { it.isNotBlank() }.map { part ->
            val cleaned = part.replace("'", "h")
            val hardened = cleaned.endsWith("h")
            val num = cleaned.trimEnd('h').toLong()
            if (hardened) (num or 0x80000000L) else num
        }
    }

    /**
     * Converts an xpub/tpub string to an HDNodeDto for Trezor Connect.
     * Trezor firmware expects structured HDNodeType, not raw xpub strings.
     */
    private fun xpubToHDNode(xpubStr: String, network: BitcoinNetwork): HDNodeDto {
        val key = DeterministicKey.deserializeB58(xpubStr, network)
        return HDNodeDto(
            depth = key.depth,
            fingerprint = (key.parentFingerprint.toLong() and 0xFFFFFFFFL),
            child_num = (key.childNumber.i.toLong() and 0xFFFFFFFFL),
            chain_code = key.chainCode.joinToString("") { "%02x".format(it) },
            public_key = key.pubKeyPoint.getEncoded(true).joinToString("") { "%02x".format(it) }
        )
    }

    /**
     * Parsuje origin path z Bitcoin output descriptoru.
     * Formát: wpkh([fingerprint/84h/1h/0h]xpub...) → [0x80000054, 0x80000001, 0x80000000]
     * Vrací null pokud descriptor neobsahuje origin info.
     */
    private fun parseDescriptorOrigin(descriptor: String): List<Long>? {
        // Najdi obsah hranatých závorek: [fingerprint/path]
        val bracketStart = descriptor.indexOf('[')
        val bracketEnd = descriptor.indexOf(']')
        if (bracketStart < 0 || bracketEnd < 0 || bracketEnd <= bracketStart) return null

        val inside = descriptor.substring(bracketStart + 1, bracketEnd)
        // inside = "aabbccdd/84h/1h/0h" nebo "aabbccdd/84'/1'/0'"
        val parts = inside.split("/")
        if (parts.size < 2) return null

        // Přeskočit fingerprint (první element), parsovat zbytek jako path
        val pathParts = parts.drop(1) // ["84h", "1h", "0h"]
        return pathParts.map { part ->
            val cleaned = part.replace("'", "h")
            val hardened = cleaned.endsWith("h")
            val num = cleaned.trimEnd('h').toLong()
            if (hardened) (num or 0x80000000L) else num
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
        wallet: WalletDetailDto,
        utxos: List<SelectedUtxo>,
        outputs: List<TxOutput>,
        changeAddress: String?,
        changeAmount: Long,
        changeIndex: Int? = null,
        rbf: Boolean
    ): String {
        log.debug("=== PSBT BUILD START ===")
        log.debug("wallet.type={} wallet.network={} wallet.m={} wallet.n={}", wallet.type, wallet.network, wallet.m, wallet.n)
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

        // Magic: "psbt" + 0xff
        psbt.addAll(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte()).toList())
        
        // Global: unsigned tx
        val unsignedTx = buildUnsignedTx(utxos, outputs, changeAddress, changeAmount, rbf)
        psbt.add(0x01)
        psbt.add(0x00)
        psbt.addAll(writeVarInt(unsignedTx.size.toLong()))
        psbt.addAll(unsignedTx.toList())
        psbt.add(0x00)
        
        val isMultisig = wallet.type == "MULTI_SIG" && wallet.cosigners.isNotEmpty()

        // Pre-derive cosigner chain keys once (for all inputs) — both singlesig and multisig.
        // PSBT_IN_BIP32_DERIVATION is required so Trezor can identify the signing key
        // without querying any blockchain backend.
        val cosignerChainKeys: Map<Int, List<Pair<CosignerDto, DeterministicKey>>>? =
            if (wallet.cosigners.isNotEmpty()) {
                val btcNetwork = if (wallet.network.lowercase() in listOf("mainnet", "bitcoin"))
                    BitcoinNetwork.MAINNET else BitcoinNetwork.TESTNET
                // Group by chain: 0=receive, 1=change
                mapOf(
                    0 to wallet.cosigners.map { cos ->
                        val accountKey = DeterministicKey.deserializeB58(cos.xpubRoot, btcNetwork)
                        cos to HDKeyDerivation.deriveChildKey(accountKey, 0) // chain 0 = receive
                    },
                    1 to wallet.cosigners.map { cos ->
                        val accountKey = DeterministicKey.deserializeB58(cos.xpubRoot, btcNetwork)
                        cos to HDKeyDerivation.deriveChildKey(accountKey, 1) // chain 1 = change
                    }
                )
            } else null

        // Input sections
        for (utxo in utxos) {
            // PSBT_IN_NON_WITNESS_UTXO (key 0x00) — celá předchozí transakce.
            // Trezor firmware 2.4+ vyžaduje předchozí tx pro VŠECHNY vstupy (i P2WPKH),
            // aby mohl ověřit správnost hodnoty výstupu a zobrazit správný poplatek.
            if (utxo.rawTxHex != null) {
                writeKv(psbt, PSBT_IN_NON_WITNESS_UTXO, ByteArray(0), hexToBytes(utxo.rawTxHex))
            }

            // PSBT_IN_WITNESS_UTXO (key 0x01)
            val witnessUtxo = buildWitnessUtxo(utxo.value, utxo.scriptPubKey)
            psbt.add(0x01)
            psbt.add(0x01)
            psbt.addAll(writeVarInt(witnessUtxo.size.toLong()))
            psbt.addAll(witnessUtxo.toList())

            if (cosignerChainKeys != null
                && utxo.addressIndex != null && utxo.addressType != null) {

                val chain = if (utxo.addressType == "change") 1 else 0
                val idx = utxo.addressIndex
                val m = wallet.m ?: 1
                val chainEntries = cosignerChainKeys[chain] ?: emptyList()

                // Derive child pubkeys for this input's address index
                val cosignerPubkeys = chainEntries.map { (cos, chainKey) ->
                    val childKey = HDKeyDerivation.deriveChildKey(chainKey, idx)
                    Triple(cos, childKey.pubKey, childKey) // (cosignerDto, compressedPubkey, key)
                }

                if (isMultisig) {
                    // BIP-67: sort pubkeys lexicographically for sortedmulti
                    val sorted = if (wallet.receiveDescriptor.contains("sortedmulti(")) {
                        cosignerPubkeys.sortedWith(compareBy<Triple<CosignerDto, ByteArray, DeterministicKey>> {
                            it.second.size
                        }.thenBy { bytesToHex(it.second) })
                    } else {
                        cosignerPubkeys
                    }

                    // PSBT_IN_WITNESS_SCRIPT (key 0x05) — only for P2WSH multisig
                    val witnessScript = buildMultisigWitnessScript(m, sorted.map { it.second })
                    writeKv(psbt, PSBT_IN_WITNESS_SCRIPT, ByteArray(0), witnessScript)
                }

                // PSBT_IN_BIP32_DERIVATION (key 0x06) for each cosigner — singlesig and multisig
                for ((cos, pubkey, _) in cosignerPubkeys) {
                    val bip32Value = encodeBip32Derivation(cos.fingerprint, cos.originPath, chain, idx)
                    log.debug("  BIP32 deriv: fp={} path={} chain={} idx={} pubkey={} value={}",
                        cos.fingerprint, cos.originPath, chain, idx,
                        bytesToHex(pubkey), bytesToHex(bip32Value))
                    writeKv(psbt, PSBT_IN_BIP32_DERIVATION, pubkey, bip32Value)
                }
            }

            psbt.add(0x00) // input separator
        }
        
        // Output sections
        val totalOutputs = outputs.size + (if (changeAddress != null) 1 else 0)
        for (i in 0 until totalOutputs) {
            // Add PSBT_OUT_BIP32_DERIVATION for the change output so Trezor can
            // identify it as belonging to the wallet (otherwise it shows full
            // amount as going to external recipients).
            val isChangeOutput = changeAddress != null && i == outputs.size
            if (isChangeOutput && cosignerChainKeys != null && changeIndex != null) {
                val chainEntries = cosignerChainKeys[1] ?: emptyList()
                val changePubkeys = chainEntries.map { (cos, chainKey) ->
                    val childKey = HDKeyDerivation.deriveChildKey(chainKey, changeIndex)
                    Triple(cos, childKey.pubKey, childKey)
                }
                for ((cos, pubkey, _) in changePubkeys) {
                    val bip32Value = encodeBip32Derivation(cos.fingerprint, cos.originPath, 1, changeIndex)
                    writeKv(psbt, PSBT_OUT_BIP32_DERIVATION, pubkey, bip32Value)
                }
            }
            psbt.add(0x00)
        }
        
        val result = Base64.getEncoder().encodeToString(psbt.toByteArray())
        log.debug("=== PSBT BUILD END ({} bytes raw, {} chars base64) ===", psbt.size, result.length)
        log.info("PSBT base64: {}", result)

        // Self-check: try to parse the generated PSBT to catch structural issues early
        try {
            val parsed = parsePsbt(Base64.getDecoder().decode(result))
            log.debug("PSBT self-check OK: {} inputs, {} outputs",
                parsed.inputKvs.size, parsed.outputKvs.size)
            for ((i, inputKvs) in parsed.inputKvs.withIndex()) {
                val hasNonWit = inputKvs.any { it.keyType == PSBT_IN_NON_WITNESS_UTXO }
                val hasWitUtxo = inputKvs.any { it.keyType == PSBT_IN_WITNESS_UTXO }
                val hasBip32 = inputKvs.any { it.keyType == PSBT_IN_BIP32_DERIVATION }
                log.debug("  input[{}]: NON_WIT={} WIT_UTXO={} BIP32_DERIV={}",
                    i, hasNonWit, hasWitUtxo, hasBip32)
            }
        } catch (e: Exception) {
            log.error("PSBT self-check FAILED: {}", e.message)
        }

        return result
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
            // P2WPKH (bc1q/tb1q with 20-byte program → 42 chars) or P2WSH (32-byte program → 62 chars)
            address.startsWith("bc1q") || address.startsWith("tb1q") -> {
                val decoded = bech32Decode(address)
                when (decoded.size) {
                    20 -> byteArrayOf(0x00, 0x14) + decoded   // P2WPKH: OP_0 <20 bytes>
                    32 -> byteArrayOf(0x00, 0x20) + decoded   // P2WSH:  OP_0 <32 bytes>
                    else -> {
                        log.warn("Unexpected witness program length: {} for address {}", decoded.size, address)
                        byteArrayOf(0x00, decoded.size.toByte()) + decoded
                    }
                }
            }
            // P2TR (bc1p/tb1p with 32-byte program)
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
    
    private const val PSBT_IN_NON_WITNESS_UTXO = 0x00  // celá předchozí transakce
    private const val PSBT_IN_WITNESS_UTXO = 0x01
    private const val PSBT_IN_PARTIAL_SIG = 0x02
    private const val PSBT_IN_WITNESS_SCRIPT = 0x05
    private const val PSBT_IN_BIP32_DERIVATION = 0x06
    private const val PSBT_OUT_BIP32_DERIVATION = 0x02
    private const val PSBT_GLOBAL_UNSIGNED_TX = 0x00

    // ========== Multisig PSBT helpers ==========

    /**
     * Write a single BIP-174 key-value pair into the PSBT byte list.
     * Key format: varint(len(keyType + keyData)) || keyType || keyData
     * Value format: varint(len(value)) || value
     */
    private fun writeKv(buf: MutableList<Byte>, keyType: Int, keyData: ByteArray, value: ByteArray) {
        val keyBytes = byteArrayOf(keyType.toByte()) + keyData
        buf.addAll(writeVarInt(keyBytes.size.toLong()))
        buf.addAll(keyBytes.toList())
        buf.addAll(writeVarInt(value.size.toLong()))
        buf.addAll(value.toList())
    }

    /**
     * Build multisig witness script:
     *   OP_M <push 0x21> <pubkey1> ... <push 0x21> <pubkeyN> OP_N OP_CHECKMULTISIG
     */
    private fun buildMultisigWitnessScript(m: Int, pubkeys: List<ByteArray>): ByteArray {
        val n = pubkeys.size
        val buf = mutableListOf<Byte>()

        // OP_M: OP_1=0x51, OP_2=0x52, ...
        buf.add((0x50 + m).toByte())

        for (pk in pubkeys) {
            buf.add(pk.size.toByte()) // push data length (0x21 = 33)
            buf.addAll(pk.toList())
        }

        // OP_N
        buf.add((0x50 + n).toByte())

        // OP_CHECKMULTISIG
        buf.add(0xAE.toByte())

        return buf.toByteArray()
    }

    /**
     * Encode BIP-32 derivation value for PSBT_IN_BIP32_DERIVATION:
     *   fingerprint (4 bytes) || path_element_1 (uint32 LE) || ... || chain (uint32 LE) || index (uint32 LE)
     *
     * @param fingerprint hex string, e.g. "aabbccdd"
     * @param originPath  e.g. "48h/0h/0h/2h" or "84'/0'/0'"
     * @param chain       0=receive, 1=change
     * @param index       address index
     */
    private fun encodeBip32Derivation(
        fingerprint: String,
        originPath: String,
        chain: Int,
        index: Int
    ): ByteArray {
        val buf = mutableListOf<Byte>()

        // 4-byte master fingerprint
        val fpBytes = hexToBytes(fingerprint.padStart(8, '0').take(8))
        buf.addAll(fpBytes.toList())

        // Parse origin path elements: "48h/0h/0h/2h" → [0x80000030, 0x80000000, 0x80000000, 0x80000002]
        val pathParts = originPath.split("/").filter { it.isNotBlank() }
        for (part in pathParts) {
            val cleaned = part.replace("'", "h")
            val hardened = cleaned.endsWith("h")
            val num = cleaned.trimEnd('h').toLong()
            val value = if (hardened) (num or 0x80000000L) else num
            buf.addAll(intToLE(value.toInt(), 4))
        }

        // Chain (0 or 1)
        buf.addAll(intToLE(chain, 4))

        // Index
        buf.addAll(intToLE(index, 4))

        return buf.toByteArray()
    }
    
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
            // CHECKMULTISIG vyžaduje, aby podpisy byly ve stejném pořadí
            // jako odpovídající pubklíče ve witness scriptu (BIP-67).
            val sortedSigs = sortSigsByWitnessScript(partialSigs, witnessScript.value)
            val items = mutableListOf<ByteArray>()
            items.add(ByteArray(0)) // OP_0 for CHECKMULTISIG bug
            for (sig in sortedSigs) {
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
     * Seřadí partial_sigs podle pozice jejich pubklíče ve witness scriptu.
     * Witness script formát: OP_M <0x21><pubkey1> ... <0x21><pubkeyN> OP_N OP_CHECKMULTISIG
     */
    private fun sortSigsByWitnessScript(sigs: List<PsbtKV>, witnessScript: ByteArray): List<PsbtKV> {
        val pubkeyOrder = mutableListOf<String>()
        var pos = 1 // přeskočí OP_M
        while (pos < witnessScript.size - 2) { // -2 pro OP_N a OP_CHECKMULTISIG
            val pushLen = witnessScript[pos].toInt() and 0xFF
            if (pushLen == 0 || pushLen > 33) break
            pos++
            if (pos + pushLen > witnessScript.size) break
            pubkeyOrder.add(bytesToHex(witnessScript.sliceArray(pos until pos + pushLen)))
            pos += pushLen
        }
        return sigs.sortedBy { sig ->
            val idx = pubkeyOrder.indexOf(bytesToHex(sig.keyData))
            if (idx == -1) Int.MAX_VALUE else idx
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
    val derivationPath: String? = null,
    val addressIndex: Int? = null,      // BIP-32 child index within chain
    val addressType: String? = null,    // "receive" (chain 0) or "change" (chain 1)
    val address: String? = null,        // the address owning this UTXO
    val rawTxHex: String? = null        // raw previous tx hex pro PSBT_IN_NON_WITNESS_UTXO
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
