package cz.majny.wallet.blockchain.http

import cz.majny.wallet.blockchain.client.MempoolBroadcastException
import cz.majny.wallet.blockchain.client.MempoolClient
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/*
 * Thin REST facade over MempoolClient. Every endpoint picks between the
 * mainnet and testnet4 backends based on the ?network query param (default
 * mainnet) and otherwise just forwards the call. No business logic lives
 * here — this service is intentionally a pass-through proxy.
 */
fun Route.blockchainRoutes(mainnet: MempoolClient, testnet: MempoolClient) {
    fun clientFor(network: String?) = if (network?.lowercase() == "testnet") testnet else mainnet

    route("/api/v1/blockchain") {

        /* GET /address/{address} — balance, tx count, UTXO count. Confirmed
         * and unconfirmed are exposed separately so the wallet can flag
         * mempool-only funds in the UI. */
        get("/address/{address}") {
            val address = call.parameters["address"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing address")
            val mempool = clientFor(call.request.queryParameters["network"])

            val info = mempool.getAddressInfo(address)
            call.respond(AddressInfoResponse(
                address = info.address,
                txCount = info.txCount,
                balance = info.balance,
                confirmedBalance = info.chain_stats.funded_txo_sum - info.chain_stats.spent_txo_sum,
                unconfirmedBalance = info.mempool_stats.funded_txo_sum - info.mempool_stats.spent_txo_sum,
                utxoCount = info.chain_stats.funded_txo_count - info.chain_stats.spent_txo_count +
                        info.mempool_stats.funded_txo_count - info.mempool_stats.spent_txo_count
            ))
        }

        /* GET /address/{address}/utxos — full UTXO list, used by coin control. */
        get("/address/{address}/utxos") {
            val address = call.parameters["address"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing address")
            val mempool = clientFor(call.request.queryParameters["network"])
            val utxos = mempool.getAddressUtxos(address)
            call.respond(utxos)
        }

        /* GET /address/{address}/txs — raw tx history, classified downstream. */
        get("/address/{address}/txs") {
            val address = call.parameters["address"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing address")
            val mempool = clientFor(call.request.queryParameters["network"])
            val txs = mempool.getAddressTransactions(address)
            call.respond(txs)
        }

        /* GET /address/{address}/has-activity — activity probe, used during
         * BIP-44 gap-limit scanning where only the boolean matters. */
        get("/address/{address}/has-activity") {
            val address = call.parameters["address"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing address")
            val mempool = clientFor(call.request.queryParameters["network"])
            val hasActivity = mempool.hasActivity(address)
            call.respond(HasActivityResponse(address = address, hasActivity = hasActivity))
        }

        /* GET /fees — current sat/vB recommendations (fastest/30 min/1 h/…). */
        get("/fees") {
            val mempool = clientFor(call.request.queryParameters["network"])
            val fees = mempool.getFeeEstimates()
            call.respond(fees)
        }

        /* GET /tip/height — current chain tip, used to convert a tx's
         * block_height into a confirmations count. */
        get("/tip/height") {
            val mempool = clientFor(call.request.queryParameters["network"])
            val height = mempool.getTipHeight()
            call.respond(TipHeightResponse(height))
        }

        /* GET /tx/{txid} — single transaction detail. */
        get("/tx/{txid}") {
            val txid = call.parameters["txid"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing txid")
            val mempool = clientFor(call.request.queryParameters["network"])
            val tx = mempool.getTransaction(txid)
            call.respond(tx)
        }

        /* GET /tx/{txid}/hex — raw tx hex. Needed so psbt-service can fill
         * PSBT_IN_NON_WITNESS_UTXO for every input; Trezor firmware 2.4+
         * requires the full previous tx even for native segwit. */
        get("/tx/{txid}/hex") {
            val txid = call.parameters["txid"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing txid")
            val mempool = clientFor(call.request.queryParameters["network"])
            val hex = mempool.getRawTransaction(txid)
            call.respond(mapOf("hex" to hex))
        }

        /* POST /tx/broadcast — push a signed raw tx. MempoolBroadcastException
         * is mapped to a 400 with the upstream body so the UI can surface the
         * real reason (e.g. insufficient fee, double spend). */
        post("/tx/broadcast") {
            val request = call.receive<BroadcastRequest>()
            val mempool = clientFor(call.request.queryParameters["network"])
            try {
                val txid = mempool.broadcastTransaction(request.hex)
                call.respond(BroadcastResponse(success = true, txid = txid))
            } catch (e: MempoolBroadcastException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    BroadcastResponse(success = false, txid = null, error = e.body)
                )
            }
        }
    }
}

// ============ Response DTOs ============

@Serializable
data class AddressInfoResponse(
    val address: String,
    val txCount: Int,
    val balance: Long,
    val confirmedBalance: Long,
    val unconfirmedBalance: Long,
    val utxoCount: Int
)

@Serializable
data class HasActivityResponse(
    val address: String,
    val hasActivity: Boolean
)

@Serializable
data class BroadcastRequest(
    val hex: String
)

@Serializable
data class BroadcastResponse(
    val success: Boolean,
    val txid: String?,
    val error: String? = null
)

@Serializable
data class TipHeightResponse(val height: Int)
