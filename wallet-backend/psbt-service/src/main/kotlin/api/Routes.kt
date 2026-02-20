package cz.majny.wallet.psbt.api

import cz.majny.wallet.psbt.builder.PsbtBuilder
import cz.majny.wallet.psbt.builder.SelectedUtxo
import cz.majny.wallet.psbt.client.BlockchainClient
import cz.majny.wallet.psbt.client.RegistryClient
import cz.majny.wallet.psbt.db.PsbtRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import java.util.*

private val log = LoggerFactory.getLogger("PsbtRoutes")

fun Route.psbtRoutes(
    repository: PsbtRepository,
    blockchainClient: BlockchainClient,
    registryClient: RegistryClient
) {
    route("/psbt") {
        
        /**
         * POST /psbt/create
         * Vytvoří novou PSBT transakci.
         */
        post("/create") {
            val appCall = call
            val request = appCall.receive<CreatePsbtRequest>()
            log.info("Creating PSBT for wallet: {}", request.walletId)
            
            try {
                // 1. Získej detail peněženky z registry
                val wallet = registryClient.getWallet(request.walletId)
                
                // 2. Získej UTXOs
                val utxos = if (request.utxos != null) {
                    // Manuální výběr (coin control)
                    selectSpecificUtxos(request.utxos, request.walletId, blockchainClient, registryClient)
                } else {
                    // Automatický výběr
                    val totalNeeded = request.outputs.sumOf { it.amountSats }
                    autoSelectUtxos(request.walletId, totalNeeded, request.feeRate, blockchainClient, registryClient)
                }
                
                if (utxos.isEmpty()) {
                    appCall.respond(HttpStatusCode.BadRequest, mapOf("error" to "No UTXOs available"))
                    return@post
                }
                
                // 3. Získej change adresu
                val changeAddress = registryClient.getChangeAddress(request.walletId)
                
                // 4. Vytvoř PSBT
                val result = PsbtBuilder.createPsbt(
                    wallet = wallet,
                    utxos = utxos,
                    outputs = request.outputs,
                    changeAddress = changeAddress.address,
                    feeRate = request.feeRate,
                    rbf = request.rbf
                )
                
                // 5. Ulož do DB
                val requiredSigs = if (wallet.type == "multisig") wallet.m ?: 1 else 1
                val id = repository.create(
                    walletId = request.walletId,
                    psbtBase64 = result.psbtBase64,
                    requiredSigs = requiredSigs,
                    label = request.label
                )
                
                appCall.respond(CreatePsbtResponse(
                    id = id.toString(),
                    psbtBase64 = result.psbtBase64,
                    estimatedFee = result.estimatedFee,
                    estimatedVsize = result.estimatedVsize
                ))
                
            } catch (e: Exception) {
                log.error("Failed to create PSBT", e)
                appCall.respond(HttpStatusCode.InternalServerError, 
                    mapOf("error" to (e.message ?: "Failed to create PSBT")))
            }
        }
        
        /**
         * GET /psbt/{id}
         * Vrátí detail konkrétního PSBT.
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
        
        /**
         * GET /psbt/wallet/{walletId}
         * Vrátí seznam PSBT pro danou peněženku.
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
        
        /**
         * POST /psbt/{id}/sign
         * Přidá podpis k existujícímu PSBT.
         * Klient pošle aktualizovaný PSBT s novým podpisem.
         */
        post("/{id}/sign") {
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
            
            val request = appCall.receive<AddSignatureRequest>()
            
            val existing = repository.findById(uuid)
            if (existing == null) {
                appCall.respond(HttpStatusCode.NotFound, mapOf("error" to "PSBT not found"))
                return@post
            }
            
            // Zkontroluj, jestli tento signer už nepodepsal
            if (existing.signatures.any { it.fingerprint == request.fingerprint }) {
                appCall.respond(HttpStatusCode.Conflict, mapOf("error" to "Already signed by this device"))
                return@post
            }
            
            // Aktualizuj PSBT a přidej podpis
            val newSigCount = existing.currentSigs + 1
            val newStatus = if (newSigCount >= existing.requiredSigs) "signed" else "pending"
            
            repository.updatePsbt(
                id = uuid,
                psbtBase64 = request.psbtBase64,
                currentSigs = newSigCount,
                status = newStatus
            )
            
            repository.addSignature(
                psbtId = uuid,
                deviceId = request.deviceId,
                fingerprint = request.fingerprint
            )
            
            val updated = repository.findById(uuid)!!
            appCall.respond(updated)
        }
        
        /**
         * POST /psbt/{id}/combine
         * Kombinuje více částečně podepsaných PSBT.
         */
        post("/{id}/combine") {
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
            
            val request = appCall.receive<CombinePsbtsRequest>()
            
            val existing = repository.findById(uuid)
            if (existing == null) {
                appCall.respond(HttpStatusCode.NotFound, mapOf("error" to "PSBT not found"))
                return@post
            }
            
            // Přidej existující PSBT do seznamu a kombinuj
            val allPsbts = listOf(existing.psbtBase64) + request.psbts
            val combined = PsbtBuilder.combinePsbts(allPsbts)
            
            // Analyzuj výsledek
            val analysis = PsbtBuilder.analyzePsbt(combined)
            val newStatus = if (analysis.isComplete) "signed" else "pending"
            
            repository.updatePsbt(
                id = uuid,
                psbtBase64 = combined,
                currentSigs = analysis.signatureCount,
                status = newStatus
            )
            
            val updated = repository.findById(uuid)!!
            appCall.respond(updated)
        }
        
        /**
         * POST /psbt/{id}/finalize
         * Finalizuje PSBT a připraví raw transakci k broadcastu.
         */
        post("/{id}/finalize") {
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
            
            val existing = repository.findById(uuid)
            if (existing == null) {
                appCall.respond(HttpStatusCode.NotFound, mapOf("error" to "PSBT not found"))
                return@post
            }
            
            // Zkontroluj, že máme dost podpisů
            if (existing.currentSigs < existing.requiredSigs) {
                appCall.respond(HttpStatusCode.BadRequest, mapOf(
                    "error" to "Not enough signatures",
                    "current" to existing.currentSigs,
                    "required" to existing.requiredSigs
                ))
                return@post
            }
            
            // Finalizuj PSBT
            val result = PsbtBuilder.finalizePsbt(existing.psbtBase64)
            
            if (!result.complete) {
                appCall.respond(HttpStatusCode.BadRequest, mapOf("error" to "Failed to finalize PSBT"))
                return@post
            }
            
            repository.markFinalized(uuid, result.txid)
            
            appCall.respond(FinalizeResponse(
                psbtId = id,
                txHex = result.txHex,
                txid = result.txid
            ))
        }
        
        /**
         * POST /psbt/{id}/broadcast
         * Broadcastuje finalizovanou transakci do sítě.
         */
        post("/{id}/broadcast") {
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
            
            val existing = repository.findById(uuid)
            if (existing == null) {
                appCall.respond(HttpStatusCode.NotFound, mapOf("error" to "PSBT not found"))
                return@post
            }
            
            if (existing.status != "finalized" && existing.status != "signed") {
                appCall.respond(HttpStatusCode.BadRequest, mapOf(
                    "error" to "PSBT must be finalized before broadcast",
                    "currentStatus" to existing.status
                ))
                return@post
            }
            
            // Finalizuj pokud ještě není
            val finalResult = if (existing.status == "signed") {
                PsbtBuilder.finalizePsbt(existing.psbtBase64)
            } else {
                // Už je finalizované, potřebujeme txHex
                PsbtBuilder.finalizePsbt(existing.psbtBase64)
            }
            
            if (finalResult.txHex.isEmpty()) {
                appCall.respond(HttpStatusCode.BadRequest, mapOf("error" to "Failed to get transaction hex"))
                return@post
            }
            
            // Broadcast
            val broadcastResult = blockchainClient.broadcastTransaction(finalResult.txHex)
            
            if (broadcastResult.error != null) {
                appCall.respond(HttpStatusCode.BadRequest, mapOf("error" to broadcastResult.error))
                return@post
            }
            
            val txid = broadcastResult.txid ?: finalResult.txid
            repository.markBroadcast(uuid, txid)
            
            appCall.respond(BroadcastResponse(
                psbtId = id,
                txid = txid,
                success = true
            ))
        }
        
        /**
         * DELETE /psbt/{id}
         * Smaže PSBT.
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
    }
}

// ========== Helpers ==========

/**
 * Vybere konkrétní UTXOs podle seznamu txid:vout.
 * Prohledá VŠECHNY adresy peněženky (receive + change).
 */
private suspend fun selectSpecificUtxos(
    selections: List<UtxoSelection>,
    walletId: String,
    blockchainClient: BlockchainClient,
    registryClient: RegistryClient
): List<SelectedUtxo> {
    val selectionSet = selections.map { "${it.txid}:${it.vout}" }.toSet()

    // Získej VŠECHNY adresy peněženky z registru
    val allAddresses = registryClient.getAllAddresses(walletId)

    // Pro každou adresu získej UTXOs a filtruj
    val result = mutableListOf<SelectedUtxo>()
    for (addrDto in allAddresses) {
        val utxos = blockchainClient.getUtxos(addrDto.address)
        for (utxo in utxos) {
            val key = "${utxo.txid}:${utxo.vout}"
            if (key in selectionSet) {
                result.add(SelectedUtxo(
                    txid = utxo.txid,
                    vout = utxo.vout,
                    value = utxo.value,
                    scriptPubKey = null,
                    addressIndex = addrDto.index,
                    addressType = addrDto.type,
                    address = addrDto.address
                ))
            }
        }
    }
    return result
}

/**
 * Automaticky vybere UTXOs pro pokrytí požadované částky + fee.
 * Prohledá VŠECHNY adresy peněženky a seřadí od největšího (largest-first).
 */
private suspend fun autoSelectUtxos(
    walletId: String,
    targetAmount: Long,
    feeRate: Double,
    blockchainClient: BlockchainClient,
    registryClient: RegistryClient
): List<SelectedUtxo> {
    // Získej VŠECHNY adresy peněženky z registru
    val allAddresses = registryClient.getAllAddresses(walletId)

    // Sbírej UTXOs ze všech adres
    data class RichUtxo(val utxo: UtxoDto, val addrDto: AddressDto)
    val allUtxos = mutableListOf<RichUtxo>()
    for (addrDto in allAddresses) {
        val utxos = blockchainClient.getUtxos(addrDto.address)
        for (utxo in utxos) {
            allUtxos.add(RichUtxo(utxo, addrDto))
        }
    }

    // Seřaď od největšího
    val sortedUtxos = allUtxos.sortedByDescending { it.utxo.value }
    
    val selected = mutableListOf<SelectedUtxo>()
    var totalSelected = 0L
    
    for (rich in sortedUtxos) {
        selected.add(SelectedUtxo(
            txid = rich.utxo.txid,
            vout = rich.utxo.vout,
            value = rich.utxo.value,
            scriptPubKey = null,
            addressIndex = rich.addrDto.index,
            addressType = rich.addrDto.type,
            address = rich.addrDto.address
        ))
        totalSelected += rich.utxo.value
        
        // Odhadni fee pro aktuální počet vstupů
        val estimatedFee = (68 * selected.size + 31 * 2 + 10) * feeRate
        
        if (totalSelected >= targetAmount + estimatedFee) {
            break
        }
    }
    
    return selected
}
