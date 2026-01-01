package cz.majny.wallet.authservice

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import cz.majny.wallet.authservice.RsaKeys
import cz.majny.wallet.authservice.JwtIssuer

fun main() {
    val port = (System.getenv("PORT") ?: "8081").toInt()

    embeddedServer(Netty, host = "0.0.0.0", port = port) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    prettyPrint = false
                    encodeDefaults = true
                }
            )
        }

        val keys = RsaKeys.fromEnvOrGenerate()
        val issuer = System.getenv("JWT_ISSUER") ?: "wallet-auth"
        val audience = System.getenv("JWT_AUDIENCE") ?: "wallet-gateway"

        val jwt = JwtIssuer(
            issuer = issuer,
            audience = audience,
            kid = keys.keyId,
            privateKey = keys.privateKey,
        )

        configureAuthRoutes(jwt, keys)
    }.start(wait = true)
}