package cz.majny.wallet.gateway.config

/* Upstream URLs and JWT settings loaded from environment at startup. */
data class AppConfig(
    val port: Int,
    val authBaseUrl: String,
    val registryBaseUrl: String,
    val explorerBaseUrl: String,
    val psbtBaseUrl: String,
    val blockchainBaseUrl: String,
    val priceBaseUrl: String,
    val jwtIssuer: String,
    val jwtAudience: String,
    val jwksUrl: String,
) {
    companion object {
        /* Reads env vars with localhost defaults (dev) — docker-compose provides
         * the service-name URLs in production. */
        fun fromEnv(): AppConfig {
            fun env(name: String, default: String) = System.getenv(name) ?: default
            val authBase = env("AUTH_BASE_URL", "http://localhost:8081")

            return AppConfig(
                port = env("PORT", "8080").toInt(),

                authBaseUrl = env("AUTH_BASE_URL", "http://localhost:8081"),
                registryBaseUrl = env("REGISTRY_BASE_URL", "http://localhost:8082"),
                explorerBaseUrl = env("EXPLORER_BASE_URL", "http://localhost:8083"),
                psbtBaseUrl = env("PSBT_BASE_URL", "http://localhost:8085"),
                blockchainBaseUrl = env("BLOCKCHAIN_BASE_URL", "http://localhost:8086"),
                priceBaseUrl = env("PRICE_BASE_URL", "http://localhost:8087"),

                jwtIssuer = env("JWT_ISSUER", "wallet-auth"),
                jwtAudience = env("JWT_AUDIENCE", "wallet-gateway"),
                jwksUrl = env("JWKS_URL", "$authBase/auth/.well-known/jwks.json"),
            )
        }
    }
}
