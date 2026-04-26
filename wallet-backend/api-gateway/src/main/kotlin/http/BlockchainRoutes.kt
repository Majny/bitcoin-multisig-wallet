package cz.majny.wallet.gateway.http

import cz.majny.wallet.gateway.deps
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/* Thin proxy routes that forward to blockchain-service. The gateway adds JWT
 * authentication; everything else is a straight pass-through. */
fun Route.blockchainRoutes() {
    authenticate("auth-jwt") {
    route("/blockchain") {

        /* GET /api/v1/blockchain/address/{address} — tx_count + balance. */
        get("/address/{address}") {
            val address = call.parameters["address"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing address")
            val info = application.deps.blockchain.getAddressInfo(address)
            call.respond(info)
        }

        /* GET /api/v1/blockchain/address/{address}/utxos — UTXO list. */
        get("/address/{address}/utxos") {
            val address = call.parameters["address"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing address")
            val utxos = application.deps.blockchain.getAddressUtxos(address)
            call.respond(utxos)
        }

        /* GET /api/v1/blockchain/address/{address}/txs — confirmed + mempool tx history. */
        get("/address/{address}/txs") {
            val address = call.parameters["address"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing address")
            val txs = application.deps.blockchain.getAddressTransactions(address)
            call.respond(txs)
        }

        /* GET /api/v1/blockchain/address/{address}/has-activity — single-call
         * activity probe used by account discovery to avoid pulling full tx lists. */
        get("/address/{address}/has-activity") {
            val address = call.parameters["address"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing address")
            val result = application.deps.blockchain.hasActivity(address)
            call.respond(result)
        }

        /* GET /api/v1/blockchain/fees — current sat/vB recommendations. */
        get("/fees") {
            val fees = application.deps.blockchain.getFeeEstimates()
            call.respond(fees)
        }

        /* GET /api/v1/blockchain/tx/{txid} — transaction metadata. */
        get("/tx/{txid}") {
            val txid = call.parameters["txid"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing txid")
            val tx = application.deps.blockchain.getTransaction(txid)
            call.respond(tx)
        }

        /* POST /api/v1/blockchain/tx/broadcast — submit raw signed tx to network. */
        post("/tx/broadcast") {
            val request = call.receive<BroadcastTxRequest>()
            val result = application.deps.blockchain.broadcastTransaction(request.hex)
            call.respond(result)
        }
    }
}

}

@Serializable
data class BroadcastTxRequest(
    val hex: String
)
