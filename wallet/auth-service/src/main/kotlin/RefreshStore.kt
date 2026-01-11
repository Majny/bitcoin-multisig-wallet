package cz.majny.wallet.authservice

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class RefreshEntry(
    val deviceId: String,
    val fingerprint: String?,
    val expiresAtEpochSec: Long
)

class RefreshStore(
    private val ttlSeconds: Long = 30L * 24 * 60 * 60 // 30 days
) {
    // TODO: Redis
    private val map = ConcurrentHashMap<String, RefreshEntry>()

    fun issue(deviceId: String, fingerprint: String?): String {
        val token = UUID.randomUUID().toString()
        val exp = Instant.now().epochSecond + ttlSeconds
        map[token] = RefreshEntry(deviceId, fingerprint, exp)
        return token
    }

    /**
     * Rotation of refresh token:
     * - get (deviceId, fp, newRefreshToken)
     */
    fun rotate(oldToken: String): Triple<String, String?, String>? {
        val entry = map[oldToken] ?: return null

        val now = Instant.now().epochSecond
        if (entry.expiresAtEpochSec <= now) {
            map.remove(oldToken)
            return null
        }

        // invalidate old
        map.remove(oldToken)

        // issue new
        val newToken = issue(entry.deviceId, entry.fingerprint)
        return Triple(entry.deviceId, entry.fingerprint, newToken)
    }
}
