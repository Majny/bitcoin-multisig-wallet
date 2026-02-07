package cz.majny.wallet.price.config

object AppConfig {
    val PORT: Int = System.getenv("PORT")?.toIntOrNull() ?: 8087
    
    // CoinGecko API (free tier, no API key needed)
    val COINGECKO_BASE_URL: String = System.getenv("COINGECKO_BASE_URL") 
        ?: "https://api.coingecko.com/api/v3"
    
    // Cache duration in milliseconds (5 minutes default)
    val PRICE_CACHE_DURATION_MS: Long = System.getenv("PRICE_CACHE_DURATION_MS")?.toLongOrNull() 
        ?: 5 * 60 * 1000L
}
