package cz.majny.wallet.price.http

import cz.majny.wallet.price.client.BitcoinPrices
import cz.majny.wallet.price.client.CoinGeckoClient
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/*
 * REST surface for BTC price data. Two endpoints - current prices and
 * sats→fiat conversion. The /health probe lives in Main.kt at the routing
 * root so docker-compose can hit /health directly without a per-service
 * special case.
 */
fun Route.priceRoutes(coinGeckoClient: CoinGeckoClient) {

    route("/price") {

        /* GET /price?currencies=czk,usd,eur - current BTC prices with 24 h
         * change. Response is whatever CoinGeckoClient has cached (or fresh
         * if TTL expired). Defaults to the three wallet-supported fiats. */
        get {
            val currenciesParam = call.request.queryParameters["currencies"] ?: "czk,usd,eur"
            val currencies = currenciesParam.split(",").map { it.trim().lowercase() }

            val prices = coinGeckoClient.getBitcoinPrices(currencies)
            call.respond(prices)
        }

        /* GET /price/convert?sats=…&currency=czk - sats → fiat. Source of
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
    }
}

@Serializable
data class ConversionResult(
    val satoshis: Long,
    val btc: Double,
    val fiatValue: Double,
    val fiatCurrency: String
)
