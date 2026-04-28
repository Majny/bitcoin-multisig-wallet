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

/*
 * Persistence for PSBTs and the per-cosigner signature audit trail. Every
 * method runs in its own Exposed transaction; callers don't need to wrap.
 */
class PsbtRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /*
     * Inserts a new PSBT row. TrezorConnectParams are stripped of `refTxs`
     * before storage — refTxs (full previous transactions) can be tens of KB
     * each and we can re-fetch them from blockchain-service when needed.
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

    /*
     * Records a cosigner's signature and bumps the PSBT counters in the same
     * transaction. INSERT into psbt_signatures runs first so the
     * UNIQUE(psbt_id, cosigner_index) constraint catches duplicate sign
     * attempts before current_sigs is touched — a previous version did the
     * UPDATE first, so a duplicated request would inflate current_sigs and
     * flip the PSBT to "signed" with a missing signature row underneath.
     *
     * On a duplicate, the underlying ExposedSQLException propagates out and
     * the whole transaction rolls back — caller maps it to a 409 Conflict.
     */
    fun signWithAudit(
        id: UUID,
        deviceId: String,
        fingerprint: String,
        cosignerIndex: Int,
        psbtBase64: String,
        requiredSigs: Int,
        trezorConnectParams: TrezorConnectParams? = null,
        serializedTx: String? = null
    ): Pair<Int, String> = transaction {
        val now = OffsetDateTime.now()

        // INSERT first — UNIQUE(psbt_id, cosigner_index) is our guard against
        // a double-sign racing past the early existence check in the route.
        PsbtSignaturesTable.insert {
            it[PsbtSignaturesTable.psbtId] = id
            it[PsbtSignaturesTable.deviceId] = deviceId
            it[PsbtSignaturesTable.fingerprint] = fingerprint
            it[PsbtSignaturesTable.cosignerIndex] = cosignerIndex
            it[signedAt] = now
        }

        // Atomic increment + payload update in one statement (only reached
        // when the audit row inserted cleanly).
        PsbtsTable.update({ PsbtsTable.id eq id }) {
            with(SqlExpressionBuilder) {
                it.update(PsbtsTable.currentSigs, PsbtsTable.currentSigs + 1)
            }
            it[PsbtsTable.psbtBase64] = psbtBase64
            it[updatedAt] = now
            if (trezorConnectParams != null) {
                it[PsbtsTable.trezorConnectParams] = json.encodeToString(
                    TrezorConnectParams.serializer(), trezorConnectParams.copy(refTxs = null)
                )
            }
            if (serializedTx != null) {
                it[PsbtsTable.serializedTx] = serializedTx
            }
        }

        // Re-read to see the post-increment count and decide the new status.
        val row = PsbtsTable.selectAll().where { PsbtsTable.id eq id }.single()
        val newSigs = row[PsbtsTable.currentSigs]
        val newStatus = if (newSigs >= requiredSigs) "signed" else "pending"

        // Status flips at most once (pending → signed); skip the write if no change.
        if (newStatus != row[PsbtsTable.status]) {
            PsbtsTable.update({ PsbtsTable.id eq id }) {
                it[status] = newStatus
            }
        }

        newSigs to newStatus
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

    /*
     * Drops pending/signed PSBTs older than the cutoff. Used by the
     * background cleanup loop to free UTXO reservations from abandoned
     * multisig drafts. Already-broadcast PSBTs are kept indefinitely
     * because they're audit history. Returns the number of rows removed.
     */
    fun deleteStale(olderThan: OffsetDateTime): Int = transaction {
        PsbtsTable.deleteWhere {
            (PsbtsTable.status inList listOf("pending", "signed")) and
                (PsbtsTable.createdAt less olderThan)
        }
    }

    /*
     * Returns "txid:vout" keys for UTXOs locked into pending/signed multisig
     * PSBTs. The Send screen uses this to skip already-reserved UTXOs and
     * surface "X BTC reserved in pending PSBTs" so the user can't accidentally
     * double-spend. Singlesig PSBTs are excluded because their flow is
     * synchronous (create → sign → broadcast in one user interaction) so
     * there's never a window where they can be double-spent.
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
                    // Corrupt params row shouldn't break reservation calculation
                    // for the rest of the wallet's PSBTs.
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
