package cz.majny.wallet.gateway.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

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

    private fun padBase64(s: String): String {
        val rem = s.length % 4
        return if (rem == 0) s else s + "=".repeat(4 - rem)
    }
}
