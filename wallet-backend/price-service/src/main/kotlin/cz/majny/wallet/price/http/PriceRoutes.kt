package cz.majny.wallet.price.http

import cz.majny.wallet.price.client.BitcoinPrices
import cz.majny.wallet.price.client.CoinGeckoClient
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/*
 * REST surface for BTC price data. Two real endpoints — current prices and
 * sats→fiat conversion — plus health checks at both /health and
 * /price/health so either is reachable depending on gateway routing.
 */
fun Route.priceRoutes(coinGeckoClient: CoinGeckoClient) {

    get("/health") {
        call.respond(mapOf("status" to "ok", "service" to "price-service"))
    }

    route("/price") {

        /* GET /price?currencies=czk,usd,eur — current BTC prices with 24 h
         * change. Response is whatever CoinGeckoClient has cached (or fresh
         * if TTL expired). Defaults to the three wallet-supported fiats. */
        get {
            val currenciesParam = call.request.queryParameters["currencies"] ?: "czk,usd,eur"
            val currencies = currenciesParam.split(",").map { it.trim().lowercase() }

            val prices = coinGeckoClient.getBitcoinPrices(currencies)
            call.respond(prices)
        }

        /* GET /price/convert?sats=…&currency=czk — sats → fiat. Source of
         * truth for the send-screen currency toggle; returns 400 on a
         * missing sats param or a currency the upstream didn't include. */
        get("/convert") {
            val sats = call.request.queryParameters["sats"]?.toLongOrNull()
            if (sats == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing or invalid 'sats' parameter"))
                return@get
            }

            val currency = call.request.queryParameters["currency"]?.lowercase() ?: "czk"
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

        /* GET /price/health — duplicate health probe under the /price route
         * so the gateway can check this service without a special case. */
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
