package cz.majny.wallet.price.http

import cz.majny.wallet.price.client.BitcoinPrices
import cz.majny.wallet.price.client.CoinGeckoClient
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/**
 * REST endpoints for Bitcoin price data.
 */
fun Route.priceRoutes(coinGeckoClient: CoinGeckoClient) {

    get("/health") {
        call.respond(mapOf("status" to "ok", "service" to "price-service"))
    }

    route("/price") {
        
        /**
         * GET /price
         * Get current Bitcoin prices in CZK, USD, EUR.
         * 
         * Query params:
         * - currencies (optional): Comma-separated currency codes (default: czk,usd,eur)
         * 
         * Response: BitcoinPrices
         */
        get {
            val currenciesParam = call.parameters["currencies"] ?: "czk,usd,eur"
            val currencies = currenciesParam.split(",").map { it.trim().lowercase() }
            
            val prices = coinGeckoClient.getBitcoinPrices(currencies)
            call.respond(prices)
        }
        
        /**
         * GET /price/convert
         * Convert satoshis to fiat value.
         * 
         * Query params:
         * - sats (required): Amount in satoshis
         * - currency (optional): Target currency (default: czk)
         * 
         * Response: ConversionResult
         */
        get("/convert") {
            val sats = call.parameters["sats"]?.toLongOrNull()
            if (sats == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing or invalid 'sats' parameter"))
                return@get
            }
            
            val currency = call.parameters["currency"]?.lowercase() ?: "czk"
            val fiatValue = coinGeckoClient.convertSatsToFiat(sats, currency)
            
            if (fiatValue == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unsupported currency: $currency"))
                return@get
            }
            
            call.respond(ConversionResult(
                satoshis = sats,
                btc = sats / 100_000_000.0,
                fiatValue = fiatValue,
                fiatCurrency = currency.uppercase()
            ))
        }
        
        /**
         * GET /price/health
         * Health check endpoint.
         */
        get("/health") {
            call.respond(mapOf("status" to "ok", "service" to "price-service"))
        }
    }
}

@Serializable
data class ConversionResult(
    val satoshis: Long,
    val btc: Double,
    val fiatValue: Double,
    val fiatCurrency: String
)
