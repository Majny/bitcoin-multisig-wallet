package cz.majny.wallet.gateway.http

import cz.majny.wallet.gateway.deps
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Routes for price data - proxies to price-service.
 */
fun Route.priceRoutes() {
    val priceClient by lazy { application.deps.price }
    
    route("/price") {
        
        /**
         * GET /api/v1/price
         * Get current Bitcoin prices in various currencies.
         * 
         * Query params:
         * - currencies (optional): Comma-separated currency codes (default: czk,usd,eur)
         */
        get {
            val currencies = call.parameters["currencies"] ?: "czk,usd,eur"
            val prices = priceClient.getBitcoinPrices(currencies)
            call.respond(prices)
        }
        
        /**
         * GET /api/v1/price/convert
         * Convert satoshis to fiat value.
         * 
         * Query params:
         * - sats (required): Amount in satoshis
         * - currency (optional): Target currency (default: czk)
         */
        get("/convert") {
            val sats = call.parameters["sats"]?.toLongOrNull()
            if (sats == null) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Missing or invalid 'sats' parameter"))
                return@get
            }
            
            val currency = call.parameters["currency"] ?: "czk"
            val result = priceClient.convertSatsToFiat(sats, currency)
            call.respond(result)
        }
    }
}
