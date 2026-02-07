package cz.majny.wallet.gateway.clients

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

/**
 * Client for price-service microservice.
 * Fetches Bitcoin price data from the dedicated price service.
 */
interface PriceClient {
    suspend fun getBitcoinPrices(currencies: String = "czk,usd,eur"): BitcoinPricesResponse
    suspend fun convertSatsToFiat(sats: Long, currency: String = "czk"): ConversionResultResponse
}

// ============ Response DTOs ============

@Serializable
data class BitcoinPricesResponse(
    val czk: Double? = null,
    val usd: Double? = null,
    val eur: Double? = null,
    val czk24hChange: Double? = null,
    val usd24hChange: Double? = null,
    val eur24hChange: Double? = null,
    val lastUpdatedAt: Long? = null,
    val cachedAt: Long? = null
)

@Serializable
data class ConversionResultResponse(
    val satoshis: Long,
    val btc: Double,
    val fiatValue: Double,
    val fiatCurrency: String
)

// ============ Implementation ============

class HttpPriceClient(
    private val baseUrl: String,
    private val httpClient: HttpClient
) : PriceClient {
    
    override suspend fun getBitcoinPrices(currencies: String): BitcoinPricesResponse {
        val response: HttpResponse = httpClient.get("$baseUrl/price") {
            parameter("currencies", currencies)
        }
        
        if (!response.status.isSuccess()) {
            throw RuntimeException("Failed to get prices: ${response.status}")
        }
        
        return response.body()
    }
    
    override suspend fun convertSatsToFiat(sats: Long, currency: String): ConversionResultResponse {
        val response: HttpResponse = httpClient.get("$baseUrl/price/convert") {
            parameter("sats", sats)
            parameter("currency", currency)
        }
        
        if (!response.status.isSuccess()) {
            throw RuntimeException("Failed to convert: ${response.status}")
        }
        
        return response.body()
    }
}
