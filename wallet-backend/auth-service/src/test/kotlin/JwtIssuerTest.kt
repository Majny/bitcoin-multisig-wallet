package org.example.jwt

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import cz.majny.wallet.authservice.JwtIssuer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date

class JwtIssuerTest {

    private fun rsaKeypair(): Pair<RSAPublicKey, RSAPrivateKey> {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp = gen.generateKeyPair()
        return kp.public as RSAPublicKey to kp.private as RSAPrivateKey
    }

    @Test
    fun `issues RS256 access token with required claims and verifies with public key`() {
        val (pub, priv) = rsaKeypair()

        val issuer = "wallet-auth"
        val audience = "wallet-gateway"
        val kid = "test-kid"

        val jwtIssuer = JwtIssuer(
            issuer = issuer,
            audience = audience,
            kid = kid,
            privateKey = priv,
            accessTtlSeconds = 15 * 60
        )

        val token = jwtIssuer.issueAccessToken(deviceId = "dev-123", fingerprint = "f00dbabe")

        val verifier = JWT.require(Algorithm.RSA256(pub, null))
            .withIssuer(issuer)
            .withAudience(audience)
            .build()

        val decoded = verifier.verify(token)

        assertEquals(kid, decoded.keyId)
        assertEquals("dev-123", decoded.subject)
        assertEquals("dev-123", decoded.getClaim("device_id").asString())
        assertEquals("f00dbabe", decoded.getClaim("fingerprint").asString())

        assertNotNull(decoded.issuedAt)
        assertNotNull(decoded.expiresAt)
        assertTrue(decoded.expiresAt.after(Date.from(Instant.now())))
    }

    @Test
    fun `fingerprint claim is omitted when null`() {
        val (pub, priv) = rsaKeypair()

        val issuer = "wallet-auth"
        val audience = "wallet-gateway"
        val kid = "test-kid"

        val jwtIssuer = JwtIssuer(
            issuer = issuer,
            audience = audience,
            kid = kid,
            privateKey = priv,
            accessTtlSeconds = 15 * 60
        )

        val token = jwtIssuer.issueAccessToken(deviceId = "dev-123", fingerprint = null)

        val verifier = JWT.require(Algorithm.RSA256(pub, null))
            .withIssuer(issuer)
            .withAudience(audience)
            .build()

        val decoded = verifier.verify(token)

        val fp = decoded.getClaim("fingerprint")
        assertTrue(fp.isMissing, "fp claim should be missing when fingerprint is null")
        assertNull(fp.asString(), "fp.asString() should be null when claim is missing")

        // Extra strict check - guard against fingerprint claim leaking back in.
        assertFalse(decoded.claims.containsKey("fingerprint"))
    }


    @Test
    fun `verification fails for wrong issuer`() {
        val (pub, priv) = rsaKeypair()

        val jwtIssuer = JwtIssuer(
            issuer = "wallet-auth",
            audience = "wallet-gateway",
            kid = "kid",
            privateKey = priv
        )

        val token = jwtIssuer.issueAccessToken("dev-1", "abcd")

        val verifierWrongIssuer = JWT.require(Algorithm.RSA256(pub, null))
            .withIssuer("some-other-issuer")
            .withAudience("wallet-gateway")
            .build()

        assertThrows(JWTVerificationException::class.java) {
            verifierWrongIssuer.verify(token)
        }
    }

    @Test
    fun `verification fails for wrong audience`() {
        val (pub, priv) = rsaKeypair()

        val jwtIssuer = JwtIssuer(
            issuer = "wallet-auth",
            audience = "wallet-gateway",
            kid = "kid",
            privateKey = priv
        )

        val token = jwtIssuer.issueAccessToken("dev-1", null)

        val verifierWrongAud = JWT.require(Algorithm.RSA256(pub, null))
            .withIssuer("wallet-auth")
            .withAudience("some-other-audience")
            .build()

        assertThrows(JWTVerificationException::class.java) {
            verifierWrongAud.verify(token)
        }
    }

    @Test
    fun `expired token is rejected`() {
        val (pub, priv) = rsaKeypair()

        val jwtIssuer = JwtIssuer(
            issuer = "wallet-auth",
            audience = "wallet-gateway",
            kid = "kid",
            privateKey = priv,
            accessTtlSeconds = 1
        )

        val token = jwtIssuer.issueAccessToken("dev-1", null)

        // Wait until the token expires - minimal but stable sleep.
        Thread.sleep(1200)

        val verifier = JWT.require(Algorithm.RSA256(pub, null))
            .withIssuer("wallet-auth")
            .withAudience("wallet-gateway")
            .build()

        assertThrows(TokenExpiredException::class.java) {
            verifier.verify(token)
        }
    }
}
