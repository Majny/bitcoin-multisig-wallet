package cz.majny.wallet.authservice

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID

object RefreshTokensTable : Table("refresh_tokens") {
    val tokenHash = text("token_hash")
    val deviceId = text("device_id")
    val fingerprint = text("fingerprint").nullable()
    val expiresAt = timestampWithTimeZone("expires_at")
    val createdAt = timestampWithTimeZone("created_at")
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()
    override val primaryKey = PrimaryKey(tokenHash)
}

class RefreshStore(
    private val ttlSeconds: Long = 30L * 24 * 60 * 60 // 30 days
) {

    fun issue(deviceId: String, fingerprint: String?): String = transaction {
        issueInternal(deviceId, fingerprint)
    }

    /**
     * Single-use rotation. Returns (deviceId, fingerprint, newToken) on success,
     * or null if the token is unknown, expired, or already revoked.
     *
     * Race-safe: the UPDATE WHERE clause includes `revoked_at IS NULL`, so two
     * concurrent rotates against the same token both succeed at SELECT but only
     * one wins the UPDATE. The loser sees `updated == 0` and returns null.
     */
    fun rotate(oldToken: String): Triple<String, String?, String>? = transaction {
        val hash = sha256Hex(oldToken)
        val now = OffsetDateTime.now()

        val row = RefreshTokensTable
            .selectAll()
            .where { RefreshTokensTable.tokenHash eq hash }
            .singleOrNull()
            ?: return@transaction null

        if (row[RefreshTokensTable.revokedAt] != null) return@transaction null
        if (!row[RefreshTokensTable.expiresAt].isAfter(now)) return@transaction null

        val deviceId = row[RefreshTokensTable.deviceId]
        val fingerprint = row[RefreshTokensTable.fingerprint]

        val updated = RefreshTokensTable.update({
            (RefreshTokensTable.tokenHash eq hash) and RefreshTokensTable.revokedAt.isNull()
        }) {
            it[revokedAt] = now
        }
        if (updated == 0) return@transaction null

        val newToken = issueInternal(deviceId, fingerprint)
        Triple(deviceId, fingerprint, newToken)
    }

    private fun issueInternal(deviceId: String, fingerprint: String?): String {
        val token = UUID.randomUUID().toString()
        val hash = sha256Hex(token)
        val now = OffsetDateTime.now()
        RefreshTokensTable.insert {
            it[tokenHash] = hash
            it[RefreshTokensTable.deviceId] = deviceId
            it[RefreshTokensTable.fingerprint] = fingerprint
            it[expiresAt] = now.plusSeconds(ttlSeconds)
            it[createdAt] = now
        }
        return token
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
