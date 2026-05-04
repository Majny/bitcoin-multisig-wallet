package cz.majny.wallet.authservice

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/*
 * Tests for DeviceRepository - upsert/lookup of Trezor device records.
 * The deviceId is derived deterministically from the master fingerprint
 * (see deriveDeviceId in Routes.kt), so a re-login from the same Trezor
 * must reuse the existing row and refresh the cached metadata rather
 * than insert a duplicate.
 *
 * Tests run against an in-memory H2 instance (PostgreSQL compat mode).
 */
class DeviceRepositoryTest {

    @BeforeEach
    fun setUp() {
        Database.connect(
            url = "jdbc:h2:mem:auth_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
        transaction {
            SchemaUtils.create(DevicesTable)
        }
    }

    @Test
    fun `upsertDevice inserts a new row when the deviceId is unseen`() {
        val repo = DeviceRepository()
        val resp = repo.upsertDevice(UpsertDeviceRequest(
            deviceId = "trezor:abc",
            fingerprint = "12345678",
            model = "Safe 5",
            label = "Primary"
        ))
        assertEquals("trezor:abc", resp.deviceId)
        assertEquals("12345678", resp.fingerprint)
        assertEquals("Safe 5", resp.model)
        assertEquals("Primary", resp.label)

        val rows = transaction { DevicesTable.selectAll().toList() }
        assertEquals(1, rows.size, "Insert path must create exactly one row")
        assertEquals("trezor:abc", rows[0][DevicesTable.deviceId])
    }

    @Test
    fun `upsertDevice refreshes model and label without inserting a duplicate`() {
        val repo = DeviceRepository()
        // Initial registration with no metadata.
        repo.upsertDevice(UpsertDeviceRequest(
            deviceId = "trezor:abc",
            fingerprint = "12345678",
            model = null,
            label = null
        ))
        // Re-login with updated metadata - same deviceId, must update in place.
        repo.upsertDevice(UpsertDeviceRequest(
            deviceId = "trezor:abc",
            fingerprint = "12345678",
            model = "Safe 5",
            label = "Primary"
        ))

        val rows = transaction { DevicesTable.selectAll().toList() }
        assertEquals(1, rows.size,
            "Re-login from the same Trezor must update, not insert a duplicate")
        assertEquals("Safe 5", rows[0][DevicesTable.model])
        assertEquals("Primary", rows[0][DevicesTable.label])
    }

    @Test
    fun `upsertDevice keeps separate rows for distinct deviceIds`() {
        val repo = DeviceRepository()
        repo.upsertDevice(UpsertDeviceRequest("trezor:aaa", "11111111"))
        repo.upsertDevice(UpsertDeviceRequest("trezor:bbb", "22222222"))

        val rows = transaction { DevicesTable.selectAll().toList() }
        assertEquals(2, rows.size, "Two distinct devices must produce two rows")
    }

    @Test
    fun `getDevice returns null for an unknown deviceId`() {
        val repo = DeviceRepository()
        assertNull(repo.getDevice("trezor:never-seen"))
    }

    @Test
    fun `getDevice returns previously upserted record`() {
        val repo = DeviceRepository()
        repo.upsertDevice(UpsertDeviceRequest(
            deviceId = "trezor:abc",
            fingerprint = "12345678",
            model = "Safe 5",
            label = "Primary"
        ))
        val fetched = repo.getDevice("trezor:abc")
        assertNotNull(fetched)
        assertEquals("trezor:abc", fetched.deviceId)
        assertEquals("12345678", fetched.fingerprint)
        assertEquals("Safe 5", fetched.model)
        assertEquals("Primary", fetched.label)
    }

    @Test
    fun `upsertDevice with null model and label persists nullable fields correctly`() {
        // Schema allows nullable model/label - must round-trip without coercion.
        val repo = DeviceRepository()
        repo.upsertDevice(UpsertDeviceRequest(
            deviceId = "trezor:abc",
            fingerprint = "12345678",
            model = null,
            label = null
        ))
        val fetched = repo.getDevice("trezor:abc")
        assertNotNull(fetched)
        assertNull(fetched.model)
        assertNull(fetched.label)
    }
}
