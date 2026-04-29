package cz.majny.wallet.price.config

/*
 * Runtime config. Port, CoinGecko base URL and cache TTL all fall back to
 * free-tier-friendly defaults so the service boots without any env vars.
 */
object AppConfig {
    val PORT: Int = System.getenv("PORT")?.toIntOrNull() ?: 8087

    // CoinGecko public API - no key needed on the free tier, at the cost of
    // a relatively low rate limit (hence the aggressive caching below).
    val COINGECKO_BASE_URL: String = System.getenv("COINGECKO_BASE_URL")
        ?: "https://api.coingecko.com/api/v3"

    // 5 min cache is a middle ground: fresh enough for a wallet dashboard,
    // loose enough to absorb bursts of users without hitting upstream quota.
    val PRICE_CACHE_DURATION_MS: Long = System.getenv("PRICE_CACHE_DURATION_MS")?.toLongOrNull()
        ?: 5 * 60 * 1000L
}
