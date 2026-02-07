package cz.majny.wallet.price.client

import cz.majny.wallet.price.config.AppConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Client for CoinGecko API - fetches Bitcoin prices in various fiat currencies.
 * Includes in-memory caching to respect rate limits and improve performance.
 */
class CoinGeckoClient {
    
    private val baseUrl = AppConfig.COINGECKO_BASE_URL
    private val cacheDurationMs = AppConfig.PRICE_CACHE_DURATION_MS
    
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(Logging) {
            level = LogLevel.INFO
        }
    }
    
    // Simple in-memory cache
    private var cachedPrices: BitcoinPrices? = null
    private var cacheTimestamp: Long = 0
    
    /**
     * Get Bitcoin prices in specified currencies.
     * Results are cached for [cacheDurationMs] milliseconds.
     * 
     * @param currencies List of currency codes (e.g., "czk", "usd", "eur")
     * @return Bitcoin prices in requested currencies
     */
    suspend fun getBitcoinPrices(currencies: List<String> = listOf("czk", "usd", "eur")): BitcoinPrices {
        val now = System.currentTimeMillis()
        val cached = cachedPrices
        
        // Return cached data if still valid
        if (cached != null && (now - cacheTimestamp) < cacheDurationMs) {
            return cached
        }
        
        // Fetch fresh data
        val currencyParam = currencies.joinToString(",") { it.lowercase() }
        val response: CoinGeckoResponse = httpClient.get("$baseUrl/simple/price") {
            parameter("ids", "bitcoin")
            parameter("vs_currencies", currencyParam)
            parameter("include_24hr_change", "true")
            parameter("include_last_updated_at", "true")
        }.body()
        
        val btcData = response.bitcoin
        val prices = BitcoinPrices(
            czk = btcData["czk"]?.toDouble(),
            usd = btcData["usd"]?.toDouble(),
            eur = btcData["eur"]?.toDouble(),
            czk24hChange = btcData["czk_24h_change"]?.toDouble(),
            usd24hChange = btcData["usd_24h_change"]?.toDouble(),
            eur24hChange = btcData["eur_24h_change"]?.toDouble(),
            lastUpdatedAt = btcData["last_updated_at"]?.toLong(),
            cachedAt = now
        )
        
        // Update cache
        cachedPrices = prices
        cacheTimestamp = now
        
        return prices
    }
    
    /**
     * Convert satoshis to fiat value.
     * 
     * @param satoshis Amount in satoshis
     * @param currency Target fiat currency code
     * @return Fiat value or null if currency not available
     */
    suspend fun convertSatsToFiat(satoshis: Long, currency: String): Double? {
        val prices = getBitcoinPrices(listOf(currency))
        val btcPrice = when (currency.lowercase()) {
            "czk" -> prices.czk
            "usd" -> prices.usd
            "eur" -> prices.eur
            else -> null
        } ?: return null
        
        val btcAmount = satoshis / 100_000_000.0
        return btcAmount * btcPrice
    }
    
    fun close() {
        httpClient.close()
    }
}

// ============ DTOs ============

@Serializable
data class CoinGeckoResponse(
    val bitcoin: Map<String, Double>
)

@Serializable
data class BitcoinPrices(
    val czk: Double? = null,
    val usd: Double? = null,
    val eur: Double? = null,
    val czk24hChange: Double? = null,
    val usd24hChange: Double? = null,
    val eur24hChange: Double? = null,
    val lastUpdatedAt: Long? = null,
    val cachedAt: Long? = null
)
