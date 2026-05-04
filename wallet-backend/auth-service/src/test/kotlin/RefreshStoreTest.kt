package cz.majny.wallet.authservice

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Tests for RefreshStore - persistent refresh token storage and rotation.
 *
 * The store backs the bearer credential half of the auth flow described in
 * sec:bezpecnost. Two correctness properties are explicitly load-bearing
 * for the security model and tested below:
 *
 *   1. Single-use rotation. Each token can be exchanged exactly once.
 *      A second exchange returns null (the route maps it to 401).
 *      This is enforced via UPDATE WHERE revoked_at IS NULL so that even
 *      two concurrent rotate() calls cannot both succeed.
 *
 *   2. Token plaintext is never persisted. The DB only stores SHA-256(token);
 *      a database read therefore does not yield a usable bearer credential.
 *
 * Tests run against an in-memory H2 instance (PostgreSQL compat mode) so
 * they need no external infrastructure.
 */
class RefreshStoreTest {

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    @BeforeEach
    fun setUp() {
        // Fresh in-memory DB per test - random name + DB_CLOSE_DELAY=-1 keeps
        // the schema alive across separate connections inside one test.
        Database.connect(
            url = "jdbc:h2:mem:auth_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        transaction {
            SchemaUtils.create(RefreshTokensTable)
        }
    }

    @Test
    fun `issue returns plaintext token and stores its SHA-256 hash`() {
        val store = RefreshStore()
        val token = store.issue(deviceId = "trezor:abc", fingerprint = "12345678")

        // Plaintext is non-empty and looks like a UUID (36 chars with dashes).
        assertTrue(token.isNotEmpty())
        assertEquals(36, token.length)

        val rows = transaction {
            RefreshTokensTable.selectAll().toList()
        }
        assertEquals(1, rows.size)

        val row = rows[0]
        // The DB column holds the hash, not the plaintext.
        assertEquals(sha256Hex(token), row[RefreshTokensTable.tokenHash])
        assertNotEquals(token, row[RefreshTokensTable.tokenHash],
            "Plaintext token must not equal the stored hash")
        assertEquals("trezor:abc", row[RefreshTokensTable.deviceId])
        assertEquals("12345678", row[RefreshTokensTable.fingerprint])
        assertNull(row[RefreshTokensTable.revokedAt],
            "Newly issued token must not be marked revoked")
    }

    @Test
    fun `plaintext token never appears in any database column`() {
        // Defensive scan against accidental leak of the plaintext into any
        // text column - the security model relies on the DB exposing only
        // hashes even on a complete dump.
        val store = RefreshStore()
        val token = store.issue("trezor:abc", "12345678")

        transaction {
            val rows = RefreshTokensTable.selectAll().toList()
            for (row in rows) {
                assertNotEquals(token, row[RefreshTokensTable.tokenHash])
                assertNotEquals(token, row[RefreshTokensTable.deviceId])
                assertNotEquals(token, row[RefreshTokensTable.fingerprint])
            }
        }
    }

    @Test
    fun `rotate exchanges valid token for a new pair and revokes the original`() {
        val store = RefreshStore()
        val original = store.issue("trezor:abc", "12345678")
        val rotated = store.rotate(original)

        assertNotNull(rotated, "First rotate of a fresh token must succeed")
        val (deviceId, fingerprint, newToken) = rotated
        assertEquals("trezor:abc", deviceId)
        assertEquals("12345678", fingerprint)
        assertNotEquals(original, newToken,
            "Rotation must yield a fresh token, not the original")

        // Original is now revoked.
        val originalRow = transaction {
            RefreshTokensTable.selectAll()
                .where { RefreshTokensTable.tokenHash eq sha256Hex(original) }
                .single()
        }
        assertNotNull(originalRow[RefreshTokensTable.revokedAt],
            "After rotate, the original token must have a non-null revoked_at")

        // New token is present and not revoked.
        val newRow = transaction {
            RefreshTokensTable.selectAll()
                .where { RefreshTokensTable.tokenHash eq sha256Hex(newToken) }
                .single()
        }
        assertNull(newRow[RefreshTokensTable.revokedAt],
            "The freshly rotated token must not be revoked")
    }

    @Test
    fun `rotate returns null when same token is exchanged twice`() {
        // Single-use guarantee: any second exchange of the same token fails,
        // so a leaked credential is burned on first use.
        val store = RefreshStore()
        val original = store.issue("trezor:abc", "12345678")

        val first = store.rotate(original)
        assertNotNull(first, "First rotation must succeed")

        val second = store.rotate(original)
        assertNull(second, "Second rotation of the same token must return null")
    }

    @Test
    fun `rotate returns null for an unknown token`() {
        val store = RefreshStore()
        // No token issued - any input is unknown.
        val result = store.rotate("00000000-0000-0000-0000-000000000000")
        assertNull(result, "Unknown token must return null, not throw")
    }

    @Test
    fun `rotate returns null when the token is past its expiration`() {
        // TTL = 0 → token is born already expired; rotate must reject it.
        val store = RefreshStore(ttlSeconds = 0)
        val token = store.issue("trezor:abc", "12345678")
        // Tiny pause so isAfter(now) check is unambiguously false.
        Thread.sleep(50)
        val result = store.rotate(token)
        assertNull(result, "Expired token must not rotate")
    }

    @Test
    fun `concurrent rotation of same token has exactly one winner and rest return null`() {
        // Race-safety: the UPDATE WHERE revoked_at IS NULL clause must let
        // only one of two simultaneous rotate() calls win, even if both
        // pass the SELECT-side existence check before either UPDATE runs.
        val store = RefreshStore()
        val token = store.issue("trezor:abc", "12345678")

        val attempts = 8
        val pool = Executors.newFixedThreadPool(attempts)
        val startGate = CountDownLatch(1)
        val successCount = AtomicInteger(0)
        val nullCount = AtomicInteger(0)
        try {
            val futures = (1..attempts).map {
                pool.submit {
                    startGate.await()
                    val result = store.rotate(token)
                    if (result != null) successCount.incrementAndGet()
                    else nullCount.incrementAndGet()
                }
            }
            startGate.countDown() // release all threads
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, successCount.get(),
            "Exactly one of $attempts concurrent rotations must succeed")
        assertEquals(attempts - 1, nullCount.get(),
            "All other rotations must return null")
    }

    @Test
    fun `issue without fingerprint stores null in fingerprint column`() {
        // Fingerprint is nullable in the schema; a login without fingerprint
        // (e.g. forwarding from a service that has only deviceId) must not
        // crash on insert.
        val store = RefreshStore()
        val token = store.issue("trezor:abc", fingerprint = null)
        val row = transaction {
            RefreshTokensTable.selectAll()
                .where { RefreshTokensTable.tokenHash eq sha256Hex(token) }
                .single()
        }
        assertNull(row[RefreshTokensTable.fingerprint])
    }
}
