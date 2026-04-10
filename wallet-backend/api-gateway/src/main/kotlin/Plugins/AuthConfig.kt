package cz.majny.wallet.gateway.plugins

import cz.majny.wallet.gateway.config.AppConfig
import com.auth0.jwk.JwkProviderBuilder
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import java.net.URL
import java.util.concurrent.TimeUnit

fun Application.configureAuth(cfg: AppConfig) {
    val jwkProvider = JwkProviderBuilder(URL(cfg.jwksUrl))
        .cached(10, 1, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    install(Authentication) {
        jwt("auth-jwt") {
            // issuer validuje verifier; kid vybírá provider
            verifier(jwkProvider, cfg.jwtIssuer)

            validate { cred ->
                val audOk = cred.payload.audience.contains(cfg.jwtAudience)
                val deviceId = cred.payload.getClaim("device_id")?.asString()

                if (!audOk) return@validate null
                if (deviceId.isNullOrBlank()) return@validate null

                JWTPrincipal(cred.payload)
            }
        }
    }
}
