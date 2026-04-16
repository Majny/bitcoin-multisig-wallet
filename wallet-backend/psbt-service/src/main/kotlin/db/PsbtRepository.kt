package cz.majny.wallet.psbt.db

import cz.majny.wallet.psbt.api.PsbtResponse
import cz.majny.wallet.psbt.api.SignatureInfo
import cz.majny.wallet.psbt.api.TrezorConnectParams
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*

class PsbtRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /* Creates a new PSBT record in the database. Stores TrezorConnectParams without refTxs (too large). */
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

    /* Finds a PSBT by its UUID, including all associated signatures. */
    fun findById(id: UUID): PsbtResponse? = transaction {
        val row = PsbtsTable.selectAll()
            .where { PsbtsTable.id eq id }
            .singleOrNull() ?: return@transaction null

        val signatures = getSignatures(id)
        rowToPsbtResponse(row, signatures)
    }

    /* Returns a list of PSBTs for the given wallet, optionally filtered by status. */
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

    /* Updates PSBT data after a signature is added (new sig count, status, updated params). */
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
     * Atomically increments currentSigs and updates PSBT in a single transaction.
     * Returns the new sig count. Uses SQL-level increment to prevent race conditions
     * when two cosigners sign concurrently.
     */
    fun atomicSignAndUpdate(
        id: UUID,
        psbtBase64: String,
        requiredSigs: Int,
        trezorConnectParams: TrezorConnectParams? = null,
        serializedTx: String? = null
    ): Pair<Int, String> = transaction {
        // Atomic increment: current_sigs = current_sigs + 1
        PsbtsTable.update({ PsbtsTable.id eq id }) {
            with(SqlExpressionBuilder) {
                it.update(PsbtsTable.currentSigs, PsbtsTable.currentSigs + 1)
            }
            it[PsbtsTable.psbtBase64] = psbtBase64
            it[updatedAt] = OffsetDateTime.now()
            if (trezorConnectParams != null) {
                it[PsbtsTable.trezorConnectParams] = json.encodeToString(
                    TrezorConnectParams.serializer(), trezorConnectParams.copy(refTxs = null)
                )
            }
            if (serializedTx != null) {
                it[PsbtsTable.serializedTx] = serializedTx
            }
        }

        // Read back the new value and determine status
        val row = PsbtsTable.selectAll().where { PsbtsTable.id eq id }.single()
        val newSigs = row[PsbtsTable.currentSigs]
        val newStatus = if (newSigs >= requiredSigs) "signed" else "pending"

        // Update status based on new sig count
        if (newStatus != row[PsbtsTable.status]) {
            PsbtsTable.update({ PsbtsTable.id eq id }) {
                it[status] = newStatus
            }
        }

        newSigs to newStatus
    }

    /* Records a signature from a cosigner (device fingerprint + cosigner index). */
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

    /* Marks a PSBT as broadcast and stores the resulting transaction ID. */
    fun markBroadcast(id: UUID, txid: String) = transaction {
        PsbtsTable.update({ PsbtsTable.id eq id }) {
            it[status] = "broadcast"
            it[PsbtsTable.txid] = txid
            it[broadcastAt] = OffsetDateTime.now()
            it[updatedAt] = OffsetDateTime.now()
        }
    }

    /* Deletes a PSBT record and releases its reserved UTXOs. */
    fun delete(id: UUID) = transaction {
        PsbtsTable.deleteWhere { PsbtsTable.id eq id }
    }

    /**
     * Deletes pending/signed PSBTs whose createdAt is older than [olderThan].
     * Used by the scheduled cleanup to release stale UTXO reservations left
     * behind when a user abandons a multisig signing flow. Returns the
     * number of rows removed.
     */
    fun deleteStale(olderThan: OffsetDateTime): Int = transaction {
        PsbtsTable.deleteWhere {
            (PsbtsTable.status inList listOf("pending", "signed")) and
                (PsbtsTable.createdAt less olderThan)
        }
    }

    /*
     * Returns the set of "txid:vout" UTXO keys reserved by pending/signed PSBTs
     * for the given wallet (not yet broadcast). Used to prevent double-spending.
     * Only multisig PSBTs reserve UTXOs — singlesig flow is atomic
     * (create → sign → broadcast in one user interaction) so reservation is unnecessary.
     */
    fun getReservedUtxos(walletId: String): Set<String> = transaction {
        PsbtsTable.selectAll()
            .where { PsbtsTable.walletId eq walletId }
            .andWhere { PsbtsTable.status inList listOf("pending", "signed") }
            .andWhere { PsbtsTable.requiredSigs greater 1 }
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

    /* Fetches all signature records for a PSBT. */
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

    /* Converts a database row + signatures to a PsbtResponse DTO. */
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
