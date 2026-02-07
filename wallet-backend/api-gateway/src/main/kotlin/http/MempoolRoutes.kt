package cz.majny.wallet.gateway.http

import cz.majny.wallet.gateway.deps
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/**
 * Routes for blockchain data via Mempool.space API.
 * Replaces Node Proxy routes for blockchain access.
 */
fun Route.mempoolRoutes() {
    route("/api/v1/blockchain") {
        
        /**
         * GET /api/v1/blockchain/address/{address}
         * Get address info including balance and tx count.
         */
        get("/address/{address}") {
            val address = call.parameters["address"] 
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing address")
            
            val info = application.deps.mempool.getAddressInfo(address)
            call.respond(AddressInfoResponse(
                address = info.address,
                txCount = info.txCount,
                balance = info.balance
            ))
        }
        
        /**
         * GET /api/v1/blockchain/address/{address}/utxos
         * Get UTXOs for coin control.
         */
        get("/address/{address}/utxos") {
            val address = call.parameters["address"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing address")
            
            val utxos = application.deps.mempool.getAddressUtxos(address)
            call.respond(utxos)
        }
        
        /**
         * GET /api/v1/blockchain/address/{address}/txs
         * Get transaction history for address.
         */
        get("/address/{address}/txs") {
            val address = call.parameters["address"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing address")
            
            val txs = application.deps.mempool.getAddressTransactions(address)
            call.respond(txs)
        }
        
        /**
         * GET /api/v1/blockchain/address/{address}/has-activity
         * Check if address has any transaction activity (for account discovery).
         */
        get("/address/{address}/has-activity") {
            val address = call.parameters["address"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing address")
            
            val hasActivity = application.deps.mempool.hasActivity(address)
            call.respond(HasActivityResponse(address = address, hasActivity = hasActivity))
        }
        
        /**
         * GET /api/v1/blockchain/fees
         * Get recommended fee rates.
         */
        get("/fees") {
            val fees = application.deps.mempool.getFeeEstimates()
            call.respond(fees)
        }
        
        /**
         * GET /api/v1/blockchain/tx/{txid}
         * Get transaction details.
         */
        get("/tx/{txid}") {
            val txid = call.parameters["txid"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing txid")
            
            val tx = application.deps.mempool.getTransaction(txid)
            call.respond(tx)
        }
        
        /**
         * POST /api/v1/blockchain/tx/broadcast
         * Broadcast a raw transaction.
         */
        post("/tx/broadcast") {
            val request = call.receive<BroadcastRequest>()
            
            try {
                val txid = application.deps.mempool.broadcastTransaction(request.hex)
                call.respond(BroadcastResponse(success = true, txid = txid))
            } catch (e: cz.majny.wallet.gateway.clients.MempoolBroadcastException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    BroadcastResponse(success = false, error = e.body)
                )
            }
        }
    }
}

// ============ DTOs ============

@Serializable
data class AddressInfoResponse(
    val address: String,
    val txCount: Int,
    val balance: Long
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
    val txid: String? = null,
    val error: String? = null
)
