package cz.majny.wallet.gateway.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/* Pulls the device_id claim out of a JWT payload without verifying the
 * signature. Used right after a login round-trip where we've already trusted
 * the upstream auth-service response and just need the extracted claim to
 * continue the login flow. */
object JwtClaimExtractor {
    fun deviceIdFromJwt(jwt: String): String {
        val parts = jwt.split(".")
        require(parts.size >= 2) { "Invalid JWT" }

        val payloadJson = String(
            Base64.getUrlDecoder().decode(padBase64(parts[1]))
        )

        val obj = Json.parseToJsonElement(payloadJson).jsonObject
        return obj["device_id"]?.jsonPrimitive?.content
            ?: error("JWT missing device_id claim")
    }

    /* Base64URL payloads in JWTs omit trailing '=' padding. */
    private fun padBase64(s: String): String {
        val rem = s.length % 4
        return if (rem == 0) s else s + "=".repeat(4 - rem)
    }
}
