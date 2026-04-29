package cz.majny.wallet.gateway.http

import cz.majny.wallet.gateway.deps
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/* Pass-through routes for price-service (CoinGecko proxy). */
fun Route.priceRoutes() {
    authenticate("auth-jwt") {
    val priceClient by lazy { application.deps.price }

    route("/price") {

        /* GET /api/v1/price - BTC prices in CZK/USD/EUR (or whatever ?currencies= asks). */
        get {
            val currencies = call.request.queryParameters["currencies"] ?: "czk,usd,eur"
            val prices = priceClient.getBitcoinPrices(currencies)
            call.respond(prices)
        }

        /* GET /api/v1/price/convert?sats=N&currency=czk - sats → fiat. */
        get("/convert") {
            val sats = call.request.queryParameters["sats"]?.toLongOrNull()
            if (sats == null) {
                call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Missing or invalid 'sats' parameter"))
                return@get
            }

            val currency = call.request.queryParameters["currency"] ?: "czk"
            val result = priceClient.convertSatsToFiat(sats, currency)
            call.respond(result)
        }
    }
    }
}
