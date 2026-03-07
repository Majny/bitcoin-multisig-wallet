package cz.majny.wallet.psbt.db

import cz.majny.wallet.psbt.api.PsbtResponse
import cz.majny.wallet.psbt.api.SignatureInfo
import cz.majny.wallet.psbt.api.TrezorConnectParams
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*

class PsbtRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Vytvoří nový PSBT záznam.
     */
    fun create(
        walletId: String,
        psbtBase64: String,
        requiredSigs: Int,
        label: String? = null,
        txType: String = "send",
        totalOutputSats: Long = 0,
        estimatedFeeSats: Long = 0,
        trezorConnectParams: TrezorConnectParams? = null
    ): UUID = transaction {
        val now = OffsetDateTime.now()
        // Store params without refTxs (too large for DB)
        val paramsToStore = trezorConnectParams?.copy(refTxs = null)
        PsbtsTable.insert {
            it[PsbtsTable.walletId] = walletId
            it[PsbtsTable.psbtBase64] = psbtBase64
            it[PsbtsTable.requiredSigs] = requiredSigs
            it[PsbtsTable.totalOutputSats] = totalOutputSats
            it[PsbtsTable.estimatedFeeSats] = estimatedFeeSats
            it[PsbtsTable.label] = label
            it[PsbtsTable.txType] = txType
            it[PsbtsTable.trezorConnectParams] = paramsToStore?.let { p ->
                json.encodeToString(TrezorConnectParams.serializer(), p)
            }
            it[createdAt] = now
            it[updatedAt] = now
        }[PsbtsTable.id]
    }
    
    /**
     * Najde PSBT podle ID.
     */
    fun findById(id: UUID): PsbtResponse? = transaction {
        val row = PsbtsTable.selectAll()
            .where { PsbtsTable.id eq id }
            .singleOrNull() ?: return@transaction null
        
        val signatures = getSignatures(id)
        rowToPsbtResponse(row, signatures)
    }
    
    /**
     * Vrátí seznam PSBT pro danou peněženku.
     */
    fun findByWallet(walletId: String, status: String? = null): List<PsbtResponse> = transaction {
        val query = PsbtsTable.selectAll()
            .where { PsbtsTable.walletId eq walletId }
        
        if (status != null) {
            query.andWhere { PsbtsTable.status eq status }
        }
        
        query.orderBy(PsbtsTable.createdAt, SortOrder.DESC)
            .map { row ->
                val id = row[PsbtsTable.id]
                val signatures = getSignatures(id)
                rowToPsbtResponse(row, signatures)
            }
    }
    
    /**
     * Aktualizuje PSBT data (po přidání podpisu).
     */
    fun updatePsbt(
        id: UUID,
        psbtBase64: String,
        currentSigs: Int,
        status: String? = null,
        trezorConnectParams: TrezorConnectParams? = null,
        serializedTx: String? = null
    ) = transaction {
        PsbtsTable.update({ PsbtsTable.id eq id }) {
            it[PsbtsTable.psbtBase64] = psbtBase64
            it[PsbtsTable.currentSigs] = currentSigs
            it[updatedAt] = OffsetDateTime.now()
            if (status != null) {
                it[PsbtsTable.status] = status
            }
            if (trezorConnectParams != null) {
                it[PsbtsTable.trezorConnectParams] = json.encodeToString(
                    TrezorConnectParams.serializer(), trezorConnectParams.copy(refTxs = null)
                )
            }
            if (serializedTx != null) {
                it[PsbtsTable.serializedTx] = serializedTx
            }
        }
    }
    
    /**
     * Přidá záznam o podpisu.
     */
    fun addSignature(
        psbtId: UUID,
        deviceId: String,
        fingerprint: String,
        cosignerIndex: Int = 0
    ) = transaction {
        PsbtSignaturesTable.insert {
            it[PsbtSignaturesTable.psbtId] = psbtId
            it[PsbtSignaturesTable.deviceId] = deviceId
            it[PsbtSignaturesTable.fingerprint] = fingerprint
            it[PsbtSignaturesTable.cosignerIndex] = cosignerIndex
            it[signedAt] = OffsetDateTime.now()
        }
    }
    
    /**
     * Označí PSBT jako broadcast.
     */
    fun markBroadcast(id: UUID, txid: String) = transaction {
        PsbtsTable.update({ PsbtsTable.id eq id }) {
            it[status] = "broadcast"
            it[PsbtsTable.txid] = txid
            it[broadcastAt] = OffsetDateTime.now()
            it[updatedAt] = OffsetDateTime.now()
        }
    }
    
    /**
     * Smaže PSBT.
     */
    fun delete(id: UUID) = transaction {
        PsbtsTable.deleteWhere { PsbtsTable.id eq id }
    }
    
    /**
     * Vrátí set "txid:vout" UTXO klíčů, které jsou použité v pending/signed PSBTs
     * pro danou wallet (ještě nebroadcastované).
     */
    fun getReservedUtxos(walletId: String): Set<String> = transaction {
        PsbtsTable.selectAll()
            .where { PsbtsTable.walletId eq walletId }
            .andWhere { PsbtsTable.status inList listOf("pending", "signed") }
            .mapNotNull { row -> row[PsbtsTable.trezorConnectParams] }
            .flatMap { paramsJson ->
                try {
                    val params = json.decodeFromString(TrezorConnectParams.serializer(), paramsJson)
                    params.inputs.map { "${it.prev_hash}:${it.prev_index}" }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            .toSet()
    }

    // ========== Helpers ==========
    
    private fun getSignatures(psbtId: UUID): List<SignatureInfo> =
        PsbtSignaturesTable.selectAll()
            .where { PsbtSignaturesTable.psbtId eq psbtId }
            .map { row ->
                SignatureInfo(
                    fingerprint = row[PsbtSignaturesTable.fingerprint],
                    deviceId = row[PsbtSignaturesTable.deviceId],
                    cosignerIndex = row[PsbtSignaturesTable.cosignerIndex],
                    signedAt = row[PsbtSignaturesTable.signedAt].format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                )
            }
    
    private fun rowToPsbtResponse(row: ResultRow, signatures: List<SignatureInfo>): PsbtResponse {
        val paramsJson = row[PsbtsTable.trezorConnectParams]
        val trezorParams = paramsJson?.let {
            try { json.decodeFromString(TrezorConnectParams.serializer(), it) }
            catch (_: Exception) { null }
        }
        return PsbtResponse(
            id = row[PsbtsTable.id].toString(),
            walletId = row[PsbtsTable.walletId],
            psbtBase64 = row[PsbtsTable.psbtBase64],
            status = row[PsbtsTable.status],
            requiredSigs = row[PsbtsTable.requiredSigs],
            currentSigs = row[PsbtsTable.currentSigs],
            totalOutputSats = row[PsbtsTable.totalOutputSats],
            estimatedFeeSats = row[PsbtsTable.estimatedFeeSats],
            signatures = signatures,
            label = row[PsbtsTable.label],
            txid = row[PsbtsTable.txid],
            createdAt = row[PsbtsTable.createdAt].format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            updatedAt = row[PsbtsTable.updatedAt].format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            trezorConnectParams = trezorParams,
            serializedTx = row[PsbtsTable.serializedTx]
        )
    }
}
