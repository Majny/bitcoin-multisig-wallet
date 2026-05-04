package cz.majny.wallet.psbt.db

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/*
 * Tests for PsbtRepository.signWithAudit - the atomic signature recording
 * and status flip used by the multisig coordination logic.
 *
 * The function must hold three invariants documented in the implementation:
 *   1. INSERT into psbt_signatures runs first so the (psbt_id, cosigner_index)
 *      UNIQUE constraint catches duplicate signs before current_sigs moves.
 *   2. current_sigs increments atomically with the audit row insert; the two
 *      can never go out of sync.
 *   3. Status flips from "pending" to "signed" exactly when current_sigs
 *      crosses required_sigs.
 *
 * The test uses an in-memory H2 instance (PostgreSQL compat mode) and
 * mirrors the production UNIQUE constraint that lives in V1__init.sql but
 * is not declared in the Exposed table object.
 */
class PsbtRepositoryTest {

    @BeforeEach
    fun setUp() {
        Database.connect(
            url = "jdbc:h2:mem:psbt_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        transaction {
            SchemaUtils.create(PsbtsTable, PsbtSignaturesTable)
            // Production schema enforces UNIQUE(psbt_id, cosigner_index) via
            // the V1__init.sql migration; the Exposed table object does not
            // declare it. Reproduce the constraint here so tests exercise the
            // same race-safety guarantee that production relies on.
            exec("""ALTER TABLE psbt_signatures
                    ADD CONSTRAINT uq_psbt_cosigner UNIQUE (psbt_id, cosigner_index)""")
        }
    }

    private fun newPsbt(repo: PsbtRepository, requiredSigs: Int = 2): UUID =
        repo.create(
            walletId = "test-wallet",
            psbtBase64 = "cHNidP8...",
            requiredSigs = requiredSigs
        )

    @Test
    fun `first signature on a 2-of-3 wallet bumps current_sigs to 1 and keeps status pending`() {
        val repo = PsbtRepository()
        val id = newPsbt(repo, requiredSigs = 2)

        val (newSigs, newStatus) = repo.signWithAudit(
            id = id,
            deviceId = "trezor:abc",
            fingerprint = "11111111",
            cosignerIndex = 0,
            psbtBase64 = "cHNidP8updated",
            requiredSigs = 2
        )
        assertEquals(1, newSigs)
        assertEquals("pending", newStatus,
            "Below-threshold sign must leave the PSBT in pending state")

        val sigCount = transaction { PsbtSignaturesTable.selectAll().count() }
        assertEquals(1L, sigCount, "Exactly one audit row per signature")
    }

    @Test
    fun `reaching the threshold flips status from pending to signed`() {
        val repo = PsbtRepository()
        val id = newPsbt(repo, requiredSigs = 2)

        repo.signWithAudit(id, "trezor:a", "11111111", 0, "cHNidP8a", requiredSigs = 2)
        val (newSigs, newStatus) = repo.signWithAudit(
            id, "trezor:b", "22222222", 1, "cHNidP8b", requiredSigs = 2
        )
        assertEquals(2, newSigs, "current_sigs must increment atomically")
        assertEquals("signed", newStatus, "Reaching required_sigs must flip status to signed")

        val sigCount = transaction { PsbtSignaturesTable.selectAll().count() }
        assertEquals(2L, sigCount)
    }

    @Test
    fun `single-required signature flips status to signed on the first call`() {
        val repo = PsbtRepository()
        val id = newPsbt(repo, requiredSigs = 1)
        val (newSigs, newStatus) = repo.signWithAudit(
            id, "trezor:a", "11111111", 0, "cHNidP8a", requiredSigs = 1
        )
        assertEquals(1, newSigs)
        assertEquals("signed", newStatus,
            "1-of-1 (singlesig coordinator) must flip immediately on first sign")
    }

    @Test
    fun `duplicate sign by the same cosigner throws and does NOT inflate current_sigs`() {
        // Memory bug regression: an earlier version did UPDATE current_sigs
        // before the INSERT, so a duplicated sign request inflated the counter
        // even though the second INSERT was rejected by the UNIQUE constraint.
        // The current implementation INSERTs first; the unique violation
        // rolls back the entire transaction so current_sigs stays at 1.
        val repo = PsbtRepository()
        val id = newPsbt(repo, requiredSigs = 2)

        // First sign by cosigner 0 succeeds.
        repo.signWithAudit(id, "trezor:a", "11111111", 0, "cHNidP8a", requiredSigs = 2)

        // Second sign by SAME cosigner index must throw.
        assertFailsWith<ExposedSQLException> {
            repo.signWithAudit(id, "trezor:a", "11111111", 0, "cHNidP8a", requiredSigs = 2)
        }

        // current_sigs must NOT be 2 - the INSERT-then-UPDATE order plus
        // transaction rollback ensures the counter stays at 1.
        val (sigs, status) = transaction {
            val row = PsbtsTable.selectAll().where { PsbtsTable.id eq id }.single()
            row[PsbtsTable.currentSigs] to row[PsbtsTable.status]
        }
        assertEquals(1, sigs,
            "Duplicate sign attempt must NOT inflate current_sigs (regression guard)")
        assertEquals("pending", status,
            "Duplicate sign must NOT prematurely flip the status to signed")

        // And the audit table still has just one row for this PSBT.
        val sigCount = transaction {
            PsbtSignaturesTable.selectAll().where { PsbtSignaturesTable.psbtId eq id }.count()
        }
        assertEquals(1L, sigCount,
            "Audit trail must contain exactly one row even after duplicate attempt")
    }

    @Test
    fun `different cosigner indices for the same PSBT do not conflict`() {
        // Same device legitimately signing as cosigner 0 AND cosigner 1
        // (multi-account testing scenario from kap-testovani sec:test-prostredi)
        // must succeed without UNIQUE constraint violation.
        val repo = PsbtRepository()
        val id = newPsbt(repo, requiredSigs = 2)

        repo.signWithAudit(id, "trezor:a", "11111111", 0, "cHNidP8a", requiredSigs = 2)
        // Same device but different cosigner_index - allowed.
        repo.signWithAudit(id, "trezor:a", "11111111", 1, "cHNidP8b", requiredSigs = 2)

        val sigCount = transaction { PsbtSignaturesTable.selectAll().count() }
        assertEquals(2L, sigCount,
            "Two distinct cosigner indices must produce two audit rows")
    }

    @Test
    fun `markBroadcast sets status broadcast and records txid`() {
        val repo = PsbtRepository()
        val id = newPsbt(repo, requiredSigs = 1)
        repo.signWithAudit(id, "trezor:a", "11111111", 0, "cHNidP8a", requiredSigs = 1)

        val txid = "abc123".repeat(10).take(64)
        repo.markBroadcast(id, txid)

        val row = transaction {
            PsbtsTable.selectAll().where { PsbtsTable.id eq id }.single()
        }
        assertEquals("broadcast", row[PsbtsTable.status])
        assertEquals(txid, row[PsbtsTable.txid])
    }

    @Test
    fun `findById returns null for an unknown PSBT identifier`() {
        val repo = PsbtRepository()
        assertNull(repo.findById(UUID.randomUUID()))
    }

    @Test
    fun `findByWallet status filter narrows the result set`() {
        val repo = PsbtRepository()
        // Two pending PSBTs in the same wallet plus one that gets broadcast.
        val a = newPsbt(repo, requiredSigs = 1)
        val b = newPsbt(repo, requiredSigs = 1)
        val c = newPsbt(repo, requiredSigs = 1)
        repo.signWithAudit(c, "trezor:a", "11111111", 0, "cHNidP8c", requiredSigs = 1)
        repo.markBroadcast(c, "deadbeef".repeat(8).take(64))

        val pending = repo.findByWallet("test-wallet", status = "pending")
        val broadcast = repo.findByWallet("test-wallet", status = "broadcast")
        assertEquals(2, pending.size,
            "Pending filter must return only the two unsent PSBTs (a and b)")
        assertEquals(1, broadcast.size,
            "Broadcast filter must return only the one broadcast PSBT (c)")
    }
}
