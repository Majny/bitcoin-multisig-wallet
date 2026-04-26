package cz.majny.wallet.authservice

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.interfaces.RSAPrivateKey
import java.time.Instant
import java.util.Date
import java.util.UUID

/* Issues short-lived access tokens for the gateway to validate. Default TTL
 * is 24 h — long enough that refresh round-trips don't dominate normal use,
 * short enough that a leaked token is not a long-term liability. */
class JwtIssuer(
    private val issuer: String,
    private val audience: String,
    private val kid: String,
    private val privateKey: RSAPrivateKey,
    private val accessTtlSeconds: Long = 24 * 60 * 60,
) {
    private val alg = Algorithm.RSA256(null, privateKey)

    /* Builds an RS256 JWT carrying device_id (subject + claim) and the
     * Trezor fingerprint as a separate claim. nbf is set 2 s back so devices
     * with mild clock skew don't get a fresh token rejected as not-yet-valid. */
    fun issueAccessToken(deviceId: String, fingerprint: String?): String {
        val now = Instant.now()
        val exp = now.plusSeconds(accessTtlSeconds)
        val jti = UUID.randomUUID().toString()

        val builder = JWT.create()
            .withKeyId(kid)
            .withHeader(mapOf("typ" to "JWT"))
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(deviceId)
            .withJWTId(jti)
            .withIssuedAt(Date.from(now))
            .withNotBefore(Date.from(now.minusSeconds(2)))
            .withExpiresAt(Date.from(exp))
            .withClaim("device_id", deviceId)

        if (!fingerprint.isNullOrBlank()) {
            builder.withClaim("fingerprint", fingerprint)
        }

        return builder.sign(alg)
    }
}
