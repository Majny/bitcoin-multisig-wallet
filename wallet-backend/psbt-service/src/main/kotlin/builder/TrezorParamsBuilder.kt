package cz.majny.wallet.psbt.builder

import cz.majny.wallet.psbt.api.*
import org.bitcoinj.base.BitcoinNetwork
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.slf4j.LoggerFactory

/*
 * Builds Trezor Connect signTransaction parameters from wallet and UTXO data.
 * Trezor firmware does not accept PSBT directly — it needs a custom JSON structure
 * with derivation paths, multisig metadata, and reference transactions.
 */
object TrezorParamsBuilder {

    private val log = LoggerFactory.getLogger(TrezorParamsBuilder::class.java)

    /*
     * Builds Trezor Connect signTransaction parameters.
     * Supports singlesig P2WPKH and multisig P2WSH.
     * Returns null if the descriptor cannot be parsed or cosigners are missing.
     */
    fun build(
        wallet: WalletDetailDto,
        utxos: List<SelectedUtxo>,
        outputs: List<TxOutput>,
        changeAddress: String?,
        changeAmount: Long,
        changeIndex: Int?,
        signerCosignerIndex: Int = 0
    ): TrezorConnectParams? {
        val coin = if (wallet.network.lowercase() in listOf("mainnet", "bitcoin")) "Bitcoin" else "Testnet"

        // Build reference transactions (raw tx hex for Trezor to verify input amounts)
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
            return buildMultisigParams(
                wallet, utxos, outputs, changeAddress, changeAmount, changeIndex,
                coin, refTxs, signerCosignerIndex
            )
        }

        return buildSinglesigParams(
            wallet, utxos, outputs, changeAddress, changeAmount, changeIndex, coin, refTxs
        )
    }

    /*
     * Builds Trezor Connect params for singlesig P2WPKH transactions.
     * Each input gets address_n = origin_path + [chain, index].
     * Change output uses address_n instead of address so Trezor identifies it as internal.
     */
    private fun buildSinglesigParams(
        wallet: WalletDetailDto,
        utxos: List<SelectedUtxo>,
        outputs: List<TxOutput>,
        changeAddress: String?,
        changeAmount: Long,
        changeIndex: Int?,
        coin: String,
        refTxs: List<TrezorConnectRefTx>?
    ): TrezorConnectParams? {
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

    /*
     * Builds Trezor Connect params for multisig P2WSH transactions.
     * Each input and change output contains a multisig object with all cosigner
     * pubkeys in BIP-67 sorted order. address_n is the signer's derivation path.
     * Trezor firmware does NOT sort pubkeys internally — the order we provide must
     * exactly match the witness script order.
     */
    private fun buildMultisigParams(
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

        log.info(
            "Building multisig TrezorConnectParams: m={} n={} signer=cosigner[{}] fp={} path={}",
            m, sortedCosigners.size, signerCosignerIndex, signerCosigner.fingerprint, signerCosigner.originPath
        )

        val btcNetwork = if (coin == "Bitcoin") BitcoinNetwork.MAINNET else BitcoinNetwork.TESTNET

        // Convert each cosigner's xpub to HDNodeDto once
        val cosignerHdNodes = sortedCosigners.map { cos -> xpubToHDNode(cos.xpubRoot, btcNetwork) }
        val isSortedMulti = wallet.receiveDescriptor.contains("sortedmulti(")

        /*
         * BIP-67 sorts cosigner HDNodes by derived child pubkey at a given chain/index.
         * This must match the witness script order exactly, otherwise Trezor computes
         * a different P2WSH address and returns Failure_DataError.
         */
        fun sortedMultisigPubkeys(chain: Int, index: Int): List<TrezorConnectMultisigPubkey> {
            if (!isSortedMulti) {
                return cosignerHdNodes.map { hdNode ->
                    TrezorConnectMultisigPubkey(node = hdNode, address_n = listOf(chain.toLong(), index.toLong()))
                }
            }
            // Derive child pubkeys and sort lexicographically (BIP-67)
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

    // ========== Key Conversion Helpers ==========

    /*
     * Parses an origin path like "84h/1h/0h" or "84'/1'/0'" into a list of uint32 values.
     * Hardened indices have bit 0x80000000 set.
     */
    fun parseOriginPathToUint32(originPath: String): List<Long> {
        return originPath.split("/").filter { it.isNotBlank() }.map { part ->
            val cleaned = part.replace("'", "h")
            val hardened = cleaned.endsWith("h")
            val num = cleaned.trimEnd('h').toLong()
            if (hardened) (num or 0x80000000L) else num
        }
    }

    /*
     * Converts an xpub/tpub base58 string to an HDNodeDto for Trezor Connect.
     * Trezor firmware expects structured HDNodeType, not raw xpub strings.
     */
    fun xpubToHDNode(xpubStr: String, network: BitcoinNetwork): HDNodeDto {
        val key = DeterministicKey.deserializeB58(xpubStr, network)
        return HDNodeDto(
            depth = key.depth,
            fingerprint = (key.parentFingerprint.toLong() and 0xFFFFFFFFL),
            child_num = (key.childNumber.i.toLong() and 0xFFFFFFFFL),
            chain_code = key.chainCode.joinToString("") { "%02x".format(it) },
            public_key = key.pubKeyPoint.getEncoded(true).joinToString("") { "%02x".format(it) }
        )
    }

    /*
     * Parses origin path from a Bitcoin output descriptor.
     * E.g. wpkh([aabbccdd/84h/1h/0h]xpub...) -> [0x80000054, 0x80000001, 0x80000000]
     * Returns null if the descriptor does not contain origin info.
     */
    private fun parseDescriptorOrigin(descriptor: String): List<Long>? {
        val bracketStart = descriptor.indexOf('[')
        val bracketEnd = descriptor.indexOf(']')
        if (bracketStart < 0 || bracketEnd < 0 || bracketEnd <= bracketStart) return null

        val inside = descriptor.substring(bracketStart + 1, bracketEnd)
        val parts = inside.split("/")
        if (parts.size < 2) return null

        // Skip fingerprint (first element), parse rest as path
        val pathParts = parts.drop(1)
        return pathParts.map { part ->
            val cleaned = part.replace("'", "h")
            val hardened = cleaned.endsWith("h")
            val num = cleaned.trimEnd('h').toLong()
            if (hardened) (num or 0x80000000L) else num
        }
    }
}
