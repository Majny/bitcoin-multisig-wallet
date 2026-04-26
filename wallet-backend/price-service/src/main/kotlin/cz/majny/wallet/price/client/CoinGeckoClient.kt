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
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/*
 * CoinGecko client. Pulls BTC prices in multiple fiats from
 * /simple/price and caches the full response in-memory for
 * PRICE_CACHE_DURATION_MS (default 5 min) so the free-tier rate limit
 * isn't the bottleneck. On upstream failure the client falls back to
 * whatever stale entry is in the cache — better to serve a slightly old
 * price than to crash the wallet dashboard.
 */
class CoinGeckoClient {

    private val log = LoggerFactory.getLogger(CoinGeckoClient::class.java)
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

    // Lock-free cache slot — a single AtomicReference is enough because we
    // always replace the whole BitcoinPrices record atomically.
    private data class CacheEntry(val prices: BitcoinPrices, val timestamp: Long)
    private val cache = AtomicReference<CacheEntry?>(null)

    /*
     * Fetch current BTC prices. Returns the cached record if still fresh,
     * otherwise goes to CoinGecko. Errors fall back to stale cache when
     * available; only a cold-start failure actually throws.
     */
    suspend fun getBitcoinPrices(currencies: List<String> = listOf("czk", "usd", "eur")): BitcoinPrices {
        val now = System.currentTimeMillis()
        val cached = cache.get()

        if (cached != null && (now - cached.timestamp) < cacheDurationMs) {
            return cached.prices
        }

        return try {
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

            cache.set(CacheEntry(prices, now))
            prices
        } catch (e: Exception) {
            // Serve stale cache rather than failing — CoinGecko outages are
            // common on the free tier and a slightly old price is fine.
            if (cached != null) {
                log.warn("CoinGecko API failed, serving stale cache (age: {}s): {}",
                    (now - cached.timestamp) / 1000, e.message)
                cached.prices
            } else {
                throw e
            }
        }
    }

    /*
     * Sats → fiat. Reuses the cached price record rather than hitting
     * CoinGecko per call; returns null if the requested currency isn't one
     * of the supported three.
     */
    suspend fun convertSatsToFiat(satoshis: Long, currency: String): Double? {
        val prices = getBitcoinPrices(listOf("czk", "usd", "eur"))
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
