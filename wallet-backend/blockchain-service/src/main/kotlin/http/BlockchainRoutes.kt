package cz.majny.wallet.blockchain.http

import cz.majny.wallet.blockchain.client.MempoolBroadcastException
import cz.majny.wallet.blockchain.client.MempoolClient
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/**
 * Routes for blockchain data via Mempool.space API.
 */
fun Route.blockchainRoutes(mainnet: MempoolClient, testnet: MempoolClient) {
    fun clientFor(network: String?) = if (network?.lowercase() == "testnet") testnet else mainnet

    route("/api/v1/blockchain") {

        /**
         * GET /api/v1/blockchain/address/{address}?network=mainnet|testnet
         * Get address info including balance and tx count.
         */
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
        
        /**
         * GET /api/v1/blockchain/address/{address}/utxos?network=mainnet|testnet
         */
        get("/address/{address}/utxos") {
            val address = call.parameters["address"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing address")
            val mempool = clientFor(call.request.queryParameters["network"])
            val utxos = mempool.getAddressUtxos(address)
            call.respond(utxos)
        }

        /**
         * GET /api/v1/blockchain/address/{address}/txs?network=mainnet|testnet
         */
        get("/address/{address}/txs") {
            val address = call.parameters["address"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing address")
            val mempool = clientFor(call.request.queryParameters["network"])
            val txs = mempool.getAddressTransactions(address)
            call.respond(txs)
        }

        /**
         * GET /api/v1/blockchain/address/{address}/has-activity?network=mainnet|testnet
         */
        get("/address/{address}/has-activity") {
            val address = call.parameters["address"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing address")
            val mempool = clientFor(call.request.queryParameters["network"])
            val hasActivity = mempool.hasActivity(address)
            call.respond(HasActivityResponse(address = address, hasActivity = hasActivity))
        }

        /**
         * GET /api/v1/blockchain/fees?network=mainnet|testnet
         */
        get("/fees") {
            val mempool = clientFor(call.request.queryParameters["network"])
            val fees = mempool.getFeeEstimates()
            call.respond(fees)
        }

        /**
         * GET /api/v1/blockchain/tip/height?network=mainnet|testnet
         */
        get("/tip/height") {
            val mempool = clientFor(call.request.queryParameters["network"])
            val height = mempool.getTipHeight()
            call.respond(TipHeightResponse(height))
        }

        /**
         * GET /api/v1/blockchain/tx/{txid}?network=mainnet|testnet
         */
        get("/tx/{txid}") {
            val txid = call.parameters["txid"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing txid")
            val mempool = clientFor(call.request.queryParameters["network"])
            val tx = mempool.getTransaction(txid)
            call.respond(tx)
        }

        /**
         * GET /api/v1/blockchain/tx/{txid}/hex?network=mainnet|testnet
         * Vrátí raw hex transakce — potřebné pro PSBT_IN_NON_WITNESS_UTXO.
         */
        get("/tx/{txid}/hex") {
            val txid = call.parameters["txid"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing txid")
            val mempool = clientFor(call.request.queryParameters["network"])
            val hex = mempool.getRawTransaction(txid)
            call.respond(mapOf("hex" to hex))
        }

        /**
         * POST /api/v1/blockchain/tx/broadcast?network=mainnet|testnet
         */
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
