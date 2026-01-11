package cz.majny.wallet.authservice

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json

fun main() {
    val port = ("8081").toInt()

    embeddedServer(Netty, host = "0.0.0.0", port = port) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    prettyPrint = false
                    encodeDefaults = true
                    explicitNulls = false
                    isLenient = true
                }
            )
        }

        val keys = RsaKeys.fromEnvOrGenerate()

        val issuer = System.getenv("JWT_ISSUER") ?: "wallet-auth"
        val audience = System.getenv("JWT_AUDIENCE") ?: "wallet-gateway"
        val accessTtl = (System.getenv("JWT_ACCESS_TTL_SECONDS") ?: "900").toLong()
        val refreshTtl = (System.getenv("JWT_REFRESH_TTL_SECONDS") ?: (30L * 24 * 60 * 60).toString()).toLong()

        val jwt = JwtIssuer(
            issuer = issuer,
            audience = audience,
            kid = keys.keyId,
            privateKey = keys.privateKey,
            accessTtlSeconds = accessTtl
        )

        val refreshStore = RefreshStore(ttlSeconds = refreshTtl)

        configureAuthRoutes(jwt, keys, refreshStore)
    }.start(wait = true)
}
