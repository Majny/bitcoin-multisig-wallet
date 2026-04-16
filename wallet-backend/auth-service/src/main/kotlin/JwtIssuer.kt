package cz.majny.wallet.authservice

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.interfaces.RSAPrivateKey
import java.time.Instant
import java.util.Date
import java.util.UUID

class JwtIssuer(
    private val issuer: String,
    private val audience: String,
    private val kid: String,
    private val privateKey: RSAPrivateKey,
    private val accessTtlSeconds: Long = 24 * 60 * 60,
) {
    private val alg = Algorithm.RSA256(null, privateKey)

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
            .withNotBefore(Date.from(now.minusSeconds(2))) // tolerance clock-skew
            .withExpiresAt(Date.from(exp))
            .withClaim("device_id", deviceId)

        if (!fingerprint.isNullOrBlank()) {
            builder.withClaim("fingerprint", fingerprint)
        }

        return builder.sign(alg)
    }
}
