package cz.majny.wallet.psbt.api

import cz.majny.wallet.psbt.builder.PsbtBuilder
import cz.majny.wallet.psbt.builder.PsbtEncoding
import cz.majny.wallet.psbt.builder.SelectedUtxo
import cz.majny.wallet.psbt.builder.TrezorParamsBuilder
import cz.majny.wallet.psbt.client.BlockchainClient
import cz.majny.wallet.psbt.client.ExplorerClient
import cz.majny.wallet.psbt.client.RegistryClient
import cz.majny.wallet.psbt.db.PsbtRepository
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.bitcoinj.base.BitcoinNetwork
import org.bitcoinj.crypto.DeterministicKey
import org.slf4j.LoggerFactory
import java.util.*

private val log = LoggerFactory.getLogger("PsbtRoutes")

/*
 * True when the failure represents a Postgres UNIQUE constraint violation
 * (SQLSTATE 23505). Walks the cause chain because Exposed wraps the original
 * SQLException in its own ExposedSQLException, sometimes nested twice.
 */
private fun isUniqueViolation(e: Throwable): Boolean {
    var cur: Throwable? = e
    while (cur != null) {
        if (cur is java.sql.SQLException && cur.sqlState == "23505") return true
        cur = cur.cause
    }
    return false
}

/*
 * Trezor returns the master fingerprint as a uint32 number ("400209115") in
 * its Connect responses, while Bitcoin output descriptors carry it as an
 * 8-character lowercase hex string ("17d8b19b"). Both representations point
 * at the same four bytes. Equality across the two is the load-bearing check
 * for "is this signer me?", so we normalise everything to the descriptor
 * shape before comparing - otherwise the fp leg of resolveCosignerIdx
 * silently misses for every multisig signed by a freshly-logged-in device.
 */
private fun normalizeFingerprint(fp: String?): String? {
    if (fp.isNullOrBlank()) return null
    val asLong = fp.toLongOrNull()
    return if (asLong != null) "%08x".format(asLong) else fp.lowercase()
}

/*
 * Resolves which cosigner row in a multisig wallet corresponds to the signing
 * caller. Two real-world setups must round-trip cleanly:
 *
 *   A) N Trezors, every cosigner picked BIP-48 account 0 on their own device.
 *      Each cosigner row has a distinct fingerprint, so the fingerprint alone
 *      identifies the row.
 *   B) 1 Trezor signing as multiple cosigners via different BIP-48 accounts.
 *      All cosigner rows share the same fingerprint, so accountIndex is what
 *      separates them - fingerprint alone would always pick cosigner 0.
 *
 * The combined (fingerprint, accountIndex) match handles both at once. Single-
 * signal fallbacks stay in for older clients (no fingerprint sent) and for the
 * degenerate case where a row's originPath does not encode an account index.
 * Returns -1 when nothing matches.
 */
private fun resolveCosignerIdx(
    cosigners: List<CosignerDto>,
    fingerprint: String?,
    accountIndex: Int?
): Int {
    val sorted = cosigners.sortedBy { it.idx }
    val accountOf: (CosignerDto) -> Int? = { cos ->
        val segs = cos.originPath.replace("'", "").replace("h", "").split("/")
        if (segs.size >= 3) segs[2].toIntOrNull() else null
    }
    val target = normalizeFingerprint(fingerprint)
    val fpMatches: (CosignerDto) -> Boolean = { cos ->
        target != null && normalizeFingerprint(cos.fingerprint) == target
    }

    // Best: both signals agree. Only this path correctly handles a single
    // Trezor wearing two cosigner hats (same fingerprint, different account).
    if (!fingerprint.isNullOrBlank() && accountIndex != null) {
        val byBoth = sorted.indexOfFirst { fpMatches(it) && accountOf(it) == accountIndex }
        if (byBoth >= 0) return byBoth
    }

    // Fingerprint alone - only safe when EXACTLY one cosigner carries that
    // fingerprint. The single-Trezor-multi-account case has every cosigner
    // sharing the same fingerprint, so an indexOfFirst here would silently
    // collapse them all onto position 0 and the wrong cosigner would record
    // the signature. When the match is ambiguous, fall through and let the
    // caller's request.cosignerIndex decide.
    if (!fingerprint.isNullOrBlank()) {
        val matches = sorted.indices.filter { i -> fpMatches(sorted[i]) }
        if (matches.size == 1) return matches[0]
    }

    // Account index alone - legacy client that never sent a fingerprint.
    if (accountIndex != null) {
        val byAcc = sorted.indexOfFirst { accountOf(it) == accountIndex }
        if (byAcc >= 0) return byAcc
    }

    return -1
}

/*
 * psbt-service HTTP surface. All routes live under /psbt and are reached
 * only via api-gateway, which already enforces JWT + wallet-membership.
 */
fun Route.psbtRoutes(
    repository: PsbtRepository,
    blockchainClient: BlockchainClient,
    registryClient: RegistryClient,
    explorerClient: ExplorerClient
) {
    route("/psbt") {

        /*
         * POST /psbt/create
         * Builds a new PSBT end-to-end:
         *   1. Pull wallet detail (descriptors, cosigners) from registry.
         *   2. Pick UTXOs - manual list from coin-control, or largest-first auto.
         *   3. Fetch raw hex of every previous tx (Trezor 2.4+ requires it
         *      as PSBT_IN_NON_WITNESS_UTXO even for native segwit inputs).
         *   4. Reserve a fresh change address from explorer (no reuse).
         *   5. Assemble PSBT binary + Trezor Connect params, persist, return.
         */
        post("/create") {
            val appCall = call
            val request = try {
                appCall.receive<CreatePsbtRequest>()
            } catch (e: Exception) {
                appCall.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid request body: ${e.message}"))
                return@post
            }
            log.info("Creating PSBT for wallet: {}", request.walletId)

            // Input validation
            if (request.feeRate <= 0 || request.feeRate.isNaN() || request.feeRate.isInfinite()) {
                appCall.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid fee rate: ${request.feeRate}. Must be a positive number."))
                return@post
            }
            if (request.outputs.isEmpty()) {
                appCall.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "At least one output is required."))
                return@post
            }
            for (output in request.outputs) {
                if (output.amountSats <= 0) {
                    appCall.respond(HttpStatusCode.BadRequest,
                        mapOf("error" to "Output amount must be positive, got ${output.amountSats} for ${output.address}"))
                    return@post
                }
                if (output.amountSats < 546) {
                    appCall.respond(HttpStatusCode.BadRequest,
                        mapOf("error" to "Output amount ${output.amountSats} sats for ${output.address} is below dust limit (546 sats)"))
                    return@post
                }
                if (output.address.isBlank()) {
                    appCall.respond(HttpStatusCode.BadRequest,
                        mapOf("error" to "Output address must not be blank."))
                    return@post
                }
            }

            try {
                // 1. Fetch wallet detail from wallet-registry
                val wallet = registryClient.getWallet(request.walletId)
                log.info("Wallet: id={} type={} network={} m={} n={} cosigners={}",
                    wallet.walletId, wallet.type, wallet.network, wallet.m, wallet.n, wallet.cosigners.size)
                for (cos in wallet.cosigners) {
                    log.info("  cosigner idx={} fingerprint={} originPath={}",
                        cos.idx, cos.fingerprint, cos.originPath)
                }

                // 2. Select UTXOs (exclude those reserved by other pending/signed PSBTs)
                val reservedUtxos = repository.getReservedUtxos(request.walletId)
                if (reservedUtxos.isNotEmpty()) {
                    log.info("Reserved UTXOs (used in pending PSBTs): {}", reservedUtxos)
                }

                val utxos = if (request.utxos != null) {
                    // Manual selection (coin control)
                    selectSpecificUtxos(request.utxos, request.walletId, wallet.network, blockchainClient, registryClient)
                } else {
                    // Auto selection - largest-first, fee calculated per wallet type
                    val totalNeeded = request.outputs.sumOf { it.amountSats }
                    autoSelectUtxos(wallet, totalNeeded, request.feeRate, request.outputs.size, blockchainClient, registryClient, reservedUtxos)
                }

                if (utxos.isEmpty()) {
                    appCall.respond(HttpStatusCode.BadRequest,
                        mapOf("error" to "No UTXOs available. All funds may be reserved by pending transactions."))
                    return@post
                }

                // Trezor 2.4+ wants the full previous transaction even for
                // native segwit inputs (it independently re-derives input
                // amounts to defend against fee spoofing). Fetch them all
                // in parallel; failures fall back to null and PsbtBuilder
                // emits the input without NON_WITNESS_UTXO.
                val rawTxMap: Map<String, String?> = coroutineScope {
                    utxos.map { it.txid }.distinct().map { txid ->
                        async {
                            txid to try {
                                val hex = blockchainClient.getRawTransaction(txid, wallet.network).hex
                                val segwit = hex.length >= 12 && hex.substring(8, 12) == "0001"
                                log.info("Raw tx fetched: txid={}... {} bytes, segwit={}, start={}",
                                    txid.take(16), hex.length / 2, segwit, hex.take(16))
                                hex
                            } catch (e: Exception) {
                                log.warn("Failed to fetch raw tx {}: {}", txid, e.message)
                                null
                            }
                        }
                    }.awaitAll().toMap()
                }
                val utxosWithPrevTx = utxos.map { it.copy(rawTxHex = rawTxMap[it.txid]) }
                log.info("UTXOs with prevTx: {}/{} have rawTxHex",
                    utxosWithPrevTx.count { it.rawTxHex != null }, utxosWithPrevTx.size)

                // Explorer derives a new change address past the gap limit
                // if every pre-derived one is already used. We always burn
                // a fresh change address per tx - no reuse, even if the
                // previous one received nothing.
                val changeAddress = explorerClient.getNextChangeAddress(request.walletId)
                log.info("Next unused change address: addr={} index={}",
                    changeAddress.address, changeAddress.index)

                // 4. Build PSBT
                val result = PsbtBuilder.createPsbt(
                    wallet = wallet,
                    utxos = utxosWithPrevTx,
                    outputs = request.outputs,
                    changeAddress = changeAddress.address,
                    changeIndex = changeAddress.index,
                    feeRate = request.feeRate,
                    rbf = request.rbf
                )

                // Trezor Connect expects a position within the cosigner list
                // sorted by idx. Resolve via fingerprint first (unique per
                // device) so multisigs where every cosigner uses BIP-48
                // account 0 still place the signer at the right slot; fall
                // back to accountIndex for older clients.
                val signerCosignerIdx = if (wallet.type == "MULTI_SIG") {
                    val match = resolveCosignerIdx(
                        wallet.cosigners, request.signerFingerprint, request.signerAccountIndex
                    )
                    if (match >= 0) {
                        log.info("Mapped signer fp={} acc={} to cosignerIdx={}",
                            request.signerFingerprint, request.signerAccountIndex, match)
                        match
                    } else {
                        log.warn("Could not map signer (fp={} acc={}) to a cosigner - defaulting to 0",
                            request.signerFingerprint, request.signerAccountIndex)
                        0
                    }
                } else 0

                // 6. Build Trezor Connect signTransaction params
                val trezorParams = TrezorParamsBuilder.build(
                    wallet = wallet,
                    utxos = utxosWithPrevTx,
                    outputs = request.outputs,
                    changeAddress = if (result.changeAmount > 0) changeAddress.address else null,
                    changeAmount = result.changeAmount,
                    changeIndex = if (result.changeAmount > 0) changeAddress.index else null,
                    signerCosignerIndex = signerCosignerIdx
                )

                // 7. Store in DB
                val requiredSigs = if (wallet.type == "MULTI_SIG") wallet.m ?: 1 else 1
                val totalOutputSats = request.outputs.sumOf { it.amountSats }
                val id = repository.create(
                    walletId = request.walletId,
                    psbtBase64 = result.psbtBase64,
                    requiredSigs = requiredSigs,
                    label = request.label,
                    totalOutputSats = totalOutputSats,
                    estimatedFeeSats = result.estimatedFee,
                    trezorConnectParams = trezorParams
                )

                log.info("PSBT created id={} fee={} vsize={} base64Len={} trezorConnect={}",
                    id, result.estimatedFee, result.estimatedVsize, result.psbtBase64.length,
                    if (trezorParams != null) "${trezorParams.inputs.size}in/${trezorParams.outputs.size}out" else "null")
                appCall.respond(CreatePsbtResponse(
                    id = id.toString(),
                    psbtBase64 = result.psbtBase64,
                    estimatedFee = result.estimatedFee,
                    estimatedVsize = result.estimatedVsize,
                    trezorConnectParams = trezorParams,
                    signerCosignerIndex = signerCosignerIdx
                ))

            } catch (e: Exception) {
                log.error("Failed to create PSBT", e)
                appCall.respond(HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Failed to create PSBT")))
            }
        }

        /*
         * GET /psbt/verify-address?walletId=X&index=N&cosignerIndex=M
         * Returns Trezor Connect getAddress params for on-device address verification.
         * For multisig: includes multisig object with BIP-67 sorted cosigner pubkeys.
         * For singlesig: returns path + scriptType only.
         */
        get("/verify-address") {
            val walletId = call.request.queryParameters["walletId"]
            if (walletId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing walletId"))
                return@get
            }
            val addressIndex = call.request.queryParameters["index"]?.toIntOrNull() ?: 0
            val rawCosignerIndex = call.request.queryParameters["cosignerIndex"]?.toIntOrNull() ?: 0
            val signerAccountIndex = call.request.queryParameters["signerAccountIndex"]?.toIntOrNull()
            val signerFingerprint = call.request.queryParameters["fingerprint"]?.takeIf { it.isNotBlank() }

            try {
                val wallet = registryClient.getWallet(walletId)
                val coin = if (wallet.network.lowercase() in listOf("mainnet", "bitcoin")) "Bitcoin" else "Testnet"
                val btcNetwork = if (coin == "Bitcoin") BitcoinNetwork.MAINNET else BitcoinNetwork.TESTNET

                if (wallet.type == "MULTI_SIG") {
                    val m = wallet.m ?: 2
                    val sortedCosigners = wallet.cosigners.sortedBy { it.idx }
                    // Resolve which cosigner is asking. Fingerprint wins because
                    // it's unique per device - accountIndex collides whenever
                    // two cosigners both use BIP-48 account 0 on different
                    // Trezors. Falls back to the legacy explicit cosignerIndex
                    // when neither signal identifies a match.
                    val resolved = resolveCosignerIdx(sortedCosigners, signerFingerprint, signerAccountIndex)
                    val cosignerIndex = if (resolved >= 0) resolved else rawCosignerIndex
                    val signerCosigner = sortedCosigners.getOrNull(cosignerIndex)
                    if (signerCosigner == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid cosignerIndex"))
                        return@get
                    }
                    val signerOriginPath = TrezorParamsBuilder.parseOriginPathToUint32(signerCosigner.originPath)

                    val cosignerHdNodes = sortedCosigners.map { cos ->
                        TrezorParamsBuilder.xpubToHDNode(cos.xpubRoot, btcNetwork)
                    }
                    val isSortedMulti = wallet.receiveDescriptor.contains("sortedmulti(")

                    val pubkeys = if (isSortedMulti) {
                        val withDerived = sortedCosigners.mapIndexed { i, cos ->
                            val accountKey = DeterministicKey.deserializeB58(cos.xpubRoot, btcNetwork)
                            val chainKey = org.bitcoinj.crypto.HDKeyDerivation.deriveChildKey(accountKey, 0)
                            val childKey = org.bitcoinj.crypto.HDKeyDerivation.deriveChildKey(chainKey, addressIndex)
                            Pair(cosignerHdNodes[i], childKey.pubKey)
                        }
                        val sorted = withDerived.sortedWith(
                            compareBy<Pair<HDNodeDto, ByteArray>> { it.second.size }
                                .thenBy { it.second.joinToString("") { b -> "%02x".format(b) } }
                        )
                        sorted.map { (hdNode, _) ->
                            TrezorConnectMultisigPubkey(node = hdNode, address_n = listOf(0L, addressIndex.toLong()))
                        }
                    } else {
                        cosignerHdNodes.map { hdNode ->
                            TrezorConnectMultisigPubkey(node = hdNode, address_n = listOf(0L, addressIndex.toLong()))
                        }
                    }

                    call.respond(VerifyAddressResponse(
                        path = signerOriginPath + listOf(0L, addressIndex.toLong()),
                        coin = coin,
                        scriptType = "SPENDWITNESS",
                        multisig = TrezorConnectMultisig(
                            pubkeys = pubkeys,
                            m = m,
                            signatures = sortedCosigners.map { "" }
                        )
                    ))
                } else {
                    // Singlesig
                    val originPath = if (wallet.cosigners.isNotEmpty()) {
                        TrezorParamsBuilder.parseOriginPathToUint32(wallet.cosigners.first().originPath)
                    } else {
                        TrezorParamsBuilder.parseDescriptorOrigin(wallet.receiveDescriptor)
                            ?: run {
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No cosigner info and cannot parse descriptor"))
                                return@get
                            }
                    }
                    call.respond(VerifyAddressResponse(
                        path = originPath + listOf(0L, addressIndex.toLong()),
                        coin = coin,
                        scriptType = "SPENDWITNESS"
                    ))
                }
            } catch (e: Exception) {
                log.error("Failed to build verify-address params for wallet {}", walletId, e)
                call.respond(HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Failed to build address params")))
            }
        }

        /*
         * GET /psbt/{id}
         * Returns the detail of a specific PSBT by its UUID.
         * Called by frontend to display transaction detail and signing status.
         */
        get("/{id}") {
            val appCall = call
            val id = appCall.parameters["id"]
            if (id == null) {
                appCall.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
                return@get
            }

            val uuid = try {
                UUID.fromString(id)
            } catch (e: Exception) {
                appCall.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid id format"))
                return@get
            }

            val psbt = repository.findById(uuid)
            if (psbt == null) {
                appCall.respond(HttpStatusCode.NotFound, mapOf("error" to "PSBT not found"))
                return@get
            }

            appCall.respond(psbt)
        }

        /*
         * GET /psbt/wallet/{walletId}?status=pending
         * Returns a list of PSBTs for the given wallet, optionally filtered by status.
         * Called by frontend to show pending/signed/broadcast transactions.
         */
        get("/wallet/{walletId}") {
            val appCall = call
            val walletId = appCall.parameters["walletId"]
            if (walletId == null) {
                appCall.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing walletId"))
                return@get
            }

            val status = appCall.request.queryParameters["status"]
            val psbts = repository.findByWallet(walletId, status)

            appCall.respond(PsbtListResponse(psbts))
        }

        /*
         * POST /psbt/{id}/sign-trezor
         * Adds Trezor Connect signatures from one cosigner to a multisig PSBT.
         * Client sends DER signatures from the Trezor Connect response.
         * Backend updates the stored TrezorConnectParams with new signatures
         * so the next cosigner sees them when signing.
         *
         * BIP-67 note: multisig.pubkeys are in BIP-67 order (sorted per-input),
         * which differs from the idx order of cosigners. We match the signer's
         * public_key against pubkeys in each input to find the correct position.
         */
        post("/{id}/sign-trezor") {
            val appCall = call
            val id = appCall.parameters["id"]
            if (id == null) {
                appCall.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
                return@post
            }

            val uuid = try {
                UUID.fromString(id)
            } catch (e: Exception) {
                appCall.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid id format"))
                return@post
            }

            val request = try {
                appCall.receive<AddTrezorSignaturesRequest>()
            } catch (e: Exception) {
                appCall.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid request body: ${e.message}"))
                return@post
            }

            val existing = repository.findById(uuid)
            if (existing == null) {
                appCall.respond(HttpStatusCode.NotFound, mapOf("error" to "PSBT not found"))
                return@post
            }

            // Resolve which cosigner just signed. Fingerprint is the primary
            // signal (unique per device); accountIndex is a fallback for the
            // legacy clients that don't send the fingerprint. Falls back to
            // the explicit request.cosignerIndex if the wallet lookup itself
            // fails, so a transient registry blip doesn't block signing.
            val resolvedCosignerIndex = try {
                val wallet = registryClient.getWallet(existing.walletId)
                val match = resolveCosignerIdx(
                    wallet.cosigners, request.fingerprint, request.signerAccountIndex
                )
                if (match >= 0) {
                    log.info("sign-trezor: mapped fp={} acc={} to cosignerIdx={}",
                        request.fingerprint, request.signerAccountIndex, match)
                    match
                } else {
                    log.warn("sign-trezor: no cosigner matches fp={} acc={}, using request.cosignerIndex={}",
                        request.fingerprint, request.signerAccountIndex, request.cosignerIndex)
                    request.cosignerIndex
                }
            } catch (e: Exception) {
                log.warn("sign-trezor: failed to load wallet for cosigner mapping, using request.cosignerIndex={}",
                    request.cosignerIndex, e)
                request.cosignerIndex
            }

            // Check if this cosigner already signed (using cosignerIndex, not fingerprint,
            // because the same device can sign as multiple cosigners via different BIP-48 accounts)
            if (existing.signatures.any { it.cosignerIndex == resolvedCosignerIndex }) {
                appCall.respond(HttpStatusCode.Conflict, mapOf("error" to "Already signed by this cosigner"))
                return@post
            }

            // Get signer's root xpub to find their position in BIP-67 sorted pubkeys
            val signerXpub = try {
                val sorted = registryClient.getWallet(existing.walletId).cosigners.sortedBy { it.idx }
                sorted.getOrNull(resolvedCosignerIndex)?.xpubRoot
            } catch (e: Exception) {
                log.warn("sign-trezor: failed to get signer xpub for sig placement", e)
                null
            }

            val signerPublicKey = signerXpub?.let { xpub ->
                try {
                    val network = if (xpub.startsWith("xpub")) BitcoinNetwork.MAINNET else BitcoinNetwork.TESTNET
                    val key = DeterministicKey.deserializeB58(xpub, network)
                    key.pubKeyPoint.getEncoded(true).joinToString("") { "%02x".format(it) }
                } catch (e: Exception) {
                    log.warn("sign-trezor: failed to derive public_key from xpub", e)
                    null
                }
            }

            // Update TrezorConnectParams - place signatures at correct BIP-67 position
            val updatedParams = existing.trezorConnectParams?.let { params ->
                val updatedInputs = params.inputs.mapIndexed { inputIndex, input ->
                    val ms = input.multisig ?: return@mapIndexed input
                    val updatedSigs = ms.signatures.toMutableList()
                    val derSig = request.signatures.getOrNull(inputIndex) ?: ""
                    if (derSig.isBlank()) return@mapIndexed input

                    // Find signer's pubkey position in BIP-67 sorted pubkeys for this input
                    val sigIdx = if (signerPublicKey != null) {
                        val match = ms.pubkeys.indexOfFirst { it.node.public_key == signerPublicKey }
                        if (match >= 0) match else {
                            log.warn("sign-trezor: signer pubkey not found in input {} pubkeys, fallback to cosignerIndex={}",
                                inputIndex, resolvedCosignerIndex)
                            resolvedCosignerIndex.coerceIn(0, ms.signatures.size - 1)
                        }
                    } else {
                        resolvedCosignerIndex.coerceIn(0, ms.signatures.size - 1)
                    }

                    if (sigIdx >= updatedSigs.size) {
                        log.error("sign-trezor: sigIdx {} out of bounds for signatures list of size {} in input {}",
                            sigIdx, updatedSigs.size, inputIndex)
                        return@mapIndexed input
                    }
                    updatedSigs[sigIdx] = derSig
                    input.copy(multisig = ms.copy(signatures = updatedSigs))
                }
                params.copy(inputs = updatedInputs)
            }

            // Insert audit row + bump currentSigs atomically. The audit row
            // INSERT happens first inside the transaction, so a duplicate
            // sign attempt (same psbt × cosigner) hits the UNIQUE constraint
            // and rolls back the whole transaction - currentSigs cannot drift
            // past the actual number of recorded signatures.
            val (newSigCount, newStatus) = try {
                repository.signWithAudit(
                    id = uuid,
                    deviceId = request.fingerprint,
                    fingerprint = request.fingerprint,
                    cosignerIndex = resolvedCosignerIndex,
                    psbtBase64 = existing.psbtBase64,
                    requiredSigs = existing.requiredSigs,
                    trezorConnectParams = updatedParams,
                    serializedTx = request.serializedTx
                )
            } catch (e: Exception) {
                // Postgres SQLSTATE 23505 = unique_violation. Race past the
                // early existence check above lands here; report the same
                // 409 Conflict the early check returns so the UI flow is
                // identical regardless of which guard triggered.
                if (isUniqueViolation(e)) {
                    log.info("sign-trezor: cosigner {} already signed PSBT {} (caught at DB)",
                        resolvedCosignerIndex, id)
                    appCall.respond(HttpStatusCode.Conflict, mapOf("error" to "Already signed by this cosigner"))
                    return@post
                }
                log.error("Failed to record signature for PSBT {} cosigner {}: {}",
                    id, resolvedCosignerIndex, e.message, e)
                appCall.respond(HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to record signature: ${e.message}"))
                return@post
            }

            log.info("Trezor signatures added: psbtId={} cosigner={} newSigs={}/{} status={}",
                id, resolvedCosignerIndex, newSigCount, existing.requiredSigs, newStatus)

            val updated = repository.findById(uuid)
                ?: return@post appCall.respond(HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to fetch updated PSBT"))
            appCall.respond(updated)
        }

        /*
         * DELETE /psbt/{id}
         * Deletes a PSBT and releases its reserved UTXOs.
         * Called by frontend when user cancels a pending transaction.
         */
        delete("/{id}") {
            val appCall = call
            val id = appCall.parameters["id"]
            if (id == null) {
                appCall.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
                return@delete
            }

            val uuid = try {
                UUID.fromString(id)
            } catch (e: Exception) {
                appCall.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid id format"))
                return@delete
            }

            repository.delete(uuid)
            appCall.respond(HttpStatusCode.NoContent)
        }

        /*
         * POST /psbt/{id}/broadcast-raw
         * Broadcasts a raw signed transaction hex to the Bitcoin network.
         * Skips PSBT sign/finalize - Trezor returns a complete signed transaction.
         * Called by frontend after Trezor signs the last required signature.
         */
        post("/{id}/broadcast-raw") {
            val appCall = call
            val id = appCall.parameters["id"]
            if (id == null) {
                appCall.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
                return@post
            }

            val uuid = try {
                UUID.fromString(id)
            } catch (e: Exception) {
                appCall.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid id format"))
                return@post
            }

            val request = try {
                appCall.receive<BroadcastRawTxRequest>()
            } catch (e: Exception) {
                appCall.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid request body: ${e.message}"))
                return@post
            }

            val existing = repository.findById(uuid)
            if (existing == null) {
                appCall.respond(HttpStatusCode.NotFound, mapOf("error" to "PSBT not found"))
                return@post
            }

            // Allow broadcast-raw from "pending" for singlesig: Trezor returns a complete
            // serialized tx, so no backend sign step ever runs to update status.
            // For multisig with missing signatures, the blockchain itself will reject.
            if (existing.status == "broadcast") {
                appCall.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "PSBT already broadcast"))
                return@post
            }

            try {
                // Network must come from the wallet record. A
                // walletId-substring heuristic ("walletId.contains(testnet)")
                // works for singlesig (whose ids encode the network) but
                // silently routes descriptor-imported multisigs
                // (id = "w-{8 hex bytes}") to the wrong chain. On a registry
                // outage we'd rather return a clear 503 than guess and have
                // the broadcast fail with "Invalid transaction" from the
                // wrong network.
                val broadcastNetwork = try {
                    registryClient.getWallet(existing.walletId).network
                } catch (e: Exception) {
                    log.error("broadcast-raw: registry lookup failed for wallet {}", existing.walletId, e)
                    appCall.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "Registry unavailable, cannot determine network for broadcast. Please retry.")
                    )
                    return@post
                }
                val broadcastResult = blockchainClient.broadcastTransaction(request.txHex, broadcastNetwork)

                if (broadcastResult.error != null) {
                    appCall.respond(HttpStatusCode.BadRequest, mapOf("error" to broadcastResult.error))
                    return@post
                }

                val txid = broadcastResult.txid ?: ""
                repository.markBroadcast(uuid, txid)

                log.info("Raw tx broadcast: psbtId={} txid={}", id, txid)
                appCall.respond(BroadcastResponse(
                    psbtId = id,
                    txid = txid,
                    success = true
                ))
            } catch (e: Exception) {
                log.error("Failed to broadcast raw tx for PSBT {}", id, e)
                appCall.respond(HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Failed to broadcast")))
            }
        }

        /*
         * GET /psbt/{id}/signers
         * Joins the wallet's cosigner roster with the per-cosigner signature
         * audit trail and returns one SignerDetail per cosigner. Per-device
         * labels are intentionally null here - the gateway layers them in
         * via registry's /cosigner-labels endpoint so that each caller only
         * sees their own labels.
         */
        get("/{id}/signers") {
            val appCall = call
            val id = appCall.parameters["id"]
            if (id == null) {
                appCall.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
                return@get
            }

            val uuid = try {
                UUID.fromString(id)
            } catch (e: Exception) {
                appCall.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid id format"))
                return@get
            }

            val psbt = repository.findById(uuid)
            if (psbt == null) {
                appCall.respond(HttpStatusCode.NotFound, mapOf("error" to "PSBT not found"))
                return@get
            }

            try {
                val wallet = registryClient.getWallet(psbt.walletId)
                val signedMap = psbt.signatures.associateBy { it.cosignerIndex }

                val signers = wallet.cosigners.map { cosigner ->
                    val sig = signedMap[cosigner.idx]
                    SignerDetail(
                        fingerprint = cosigner.fingerprint,
                        cosignerIndex = cosigner.idx,
                        originPath = cosigner.originPath,
                        xpub = cosigner.xpubRoot,
                        signed = sig != null,
                        deviceId = sig?.deviceId,
                        signedAt = sig?.signedAt,
                        // Labels are per-device and are layered in by api-gateway
                        // via registry's /cosigner-labels endpoint - see PsbtRoutes.
                        label = null
                    )
                }

                appCall.respond(SignerStatusResponse(
                    psbtId = id,
                    walletId = psbt.walletId,
                    status = psbt.status,
                    requiredSigs = psbt.requiredSigs,
                    currentSigs = psbt.currentSigs,
                    signers = signers
                ))
            } catch (e: Exception) {
                log.error("Failed to get signers for PSBT {}", id, e)
                appCall.respond(HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.message ?: "Failed to get signer status")))
            }
        }
    }
}

// UTXO Selection Helpers

/*
 * Selects specific UTXOs by txid:vout from the user's coin control list.
 * If selections include an address, queries only those addresses (fast path).
 * Otherwise falls back to scanning ALL wallet addresses (slow path).
 */
private suspend fun selectSpecificUtxos(
    selections: List<UtxoSelection>,
    walletId: String,
    network: String,
    blockchainClient: BlockchainClient,
    registryClient: RegistryClient
): List<SelectedUtxo> = coroutineScope {
    val selectionSet = selections.map { "${it.txid}:${it.vout}" }.toSet()

    // Fast path: selections have addresses - query only those (no full wallet scan)
    val knownAddresses = selections.mapNotNull { it.address }.distinct()
    val addressesToScan = if (knownAddresses.size == selections.size) {
        val allAddresses = registryClient.getAllAddresses(walletId)
        val addrMap = allAddresses.associateBy { it.address }
        knownAddresses.mapNotNull { addrMap[it] }
    } else {
        // Slow path: some selections missing address - scan all wallet addresses
        registryClient.getAllAddresses(walletId)
    }

    addressesToScan.map { addrDto ->
        async {
            try {
                blockchainClient.getUtxos(addrDto.address, network)
                    .filter { utxo -> "${utxo.txid}:${utxo.vout}" in selectionSet }
                    .map { utxo ->
                        val scriptHex = PsbtEncoding.addressToScriptHex(addrDto.address).ifEmpty { null }
                        SelectedUtxo(
                            txid = utxo.txid,
                            vout = utxo.vout,
                            value = utxo.value,
                            scriptPubKey = scriptHex,
                            addressIndex = addrDto.index,
                            addressType = addrDto.type,
                            address = addrDto.address
                        )
                    }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }.awaitAll().flatten()
}

/*
 * Automatically selects UTXOs to cover the target amount + estimated fee.
 * Searches ALL wallet addresses, sorts by value descending (largest-first strategy),
 * and excludes UTXOs reserved by other pending/signed PSBTs.
 * Fee per input is calculated correctly for singlesig (68 vB) vs multisig (57+73M+34N vB).
 */
private suspend fun autoSelectUtxos(
    wallet: WalletDetailDto,
    targetAmount: Long,
    feeRate: Double,
    recipientOutputCount: Int = 1,
    blockchainClient: BlockchainClient,
    registryClient: RegistryClient,
    reservedUtxos: Set<String> = emptySet()
): List<SelectedUtxo> = coroutineScope {
    val allAddresses = registryClient.getAllAddresses(wallet.walletId)

    data class RichUtxo(val utxo: UtxoDto, val addrDto: AddressDto)
    val allUtxos = allAddresses.map { addrDto ->
        async {
            try {
                blockchainClient.getUtxos(addrDto.address, wallet.network).map { RichUtxo(it, addrDto) }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }.awaitAll().flatten()

    // Exclude UTXOs used in other pending PSBTs
    val availableUtxos = if (reservedUtxos.isNotEmpty()) {
        allUtxos.filter { "${it.utxo.txid}:${it.utxo.vout}" !in reservedUtxos }
    } else allUtxos

    val sortedUtxos = availableUtxos.sortedByDescending { it.utxo.value }

    val isMultisig = wallet.type == "MULTI_SIG"
    val m = wallet.m ?: 1
    val n = wallet.n ?: 1
    val perInputVsize = if (isMultisig) (41 + (5 + 73 * m + 34 * n) / 4) else 68

    val selected = mutableListOf<SelectedUtxo>()
    var totalSelected = 0L

    for (rich in sortedUtxos) {
        val scriptHex = PsbtEncoding.addressToScriptHex(rich.addrDto.address).ifEmpty { null }
        selected.add(SelectedUtxo(
            txid = rich.utxo.txid,
            vout = rich.utxo.vout,
            value = rich.utxo.value,
            scriptPubKey = scriptHex,
            addressIndex = rich.addrDto.index,
            addressType = rich.addrDto.type,
            address = rich.addrDto.address
        ))
        totalSelected += rich.utxo.value

        // Estimate fee: recipient outputs + 1 change output
        val outputCount = recipientOutputCount + 1
        val estimatedFee = (perInputVsize * selected.size + 31 * outputCount + 10) * feeRate

        if (totalSelected >= targetAmount + estimatedFee) {
            break
        }
    }

    selected
}
