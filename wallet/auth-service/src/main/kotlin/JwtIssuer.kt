package cz.majny.wallet.authservice

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.interfaces.RSAPrivateKey
import java.time.Instant
import java.util.Date

class JwtIssuer(
    private val issuer: String,
    private val audience: String,
    private val kid: String,
    private val privateKey: RSAPrivateKey,
    private val accessTtlSeconds: Long = 15 * 60,
) {
    private val alg = Algorithm.RSA256(null, privateKey)

    fun issueAccessToken(deviceId: String, fingerprint: String?): String {
        val now = Instant.now()
        val exp = now.plusSeconds(accessTtlSeconds)

        val builder = JWT.create()
            .withKeyId(kid)
            .withHeader(mapOf("typ" to "JWT"))
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(deviceId)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(exp))
            .withClaim("device_id", deviceId)

        if (!fingerprint.isNullOrBlank()) {
            builder.withClaim("fp", fingerprint)
        }

        return builder.sign(alg)
    }
}
