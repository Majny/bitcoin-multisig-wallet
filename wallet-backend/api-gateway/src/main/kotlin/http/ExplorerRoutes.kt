package cz.majny.wallet.gateway.http

import cz.majny.wallet.gateway.deps
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.explorerRoutes() {
    authenticate("auth-jwt") {
        
        // Legacy endpoint (zpětná kompatibilita)
        get("/wallets/{walletId}/utxos") {
            val walletId = call.parameters["walletId"] ?: error("walletId missing")
            val utxos = call.application.deps.explorer.getUtxos(walletId)
            call.respond(utxos)
        }

        route("/explorer") {
            
            // GET /api/v1/explorer/wallet/{walletId}/balance
            get("/wallet/{walletId}/balance") {
                val walletId = call.parameters["walletId"] ?: error("walletId missing")
                val balance = call.application.deps.explorer.getWalletBalance(walletId)
                call.respond(balance)
            }
            
            // GET /api/v1/explorer/wallet/{walletId}/transactions?limit=50&offset=0
            get("/wallet/{walletId}/transactions") {
                val walletId = call.parameters["walletId"] ?: error("walletId missing")
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()
                val txs = call.application.deps.explorer.getWalletTransactions(walletId, limit, offset)
                call.respond(txs)
            }
            
            // GET /api/v1/explorer/wallet/{walletId}/utxos
            get("/wallet/{walletId}/utxos") {
                val walletId = call.parameters["walletId"] ?: error("walletId missing")
                val utxos = call.application.deps.explorer.getWalletUtxos(walletId)
                call.respond(utxos)
            }
            
            // GET /api/v1/explorer/wallet/{walletId}/receive-address
            get("/wallet/{walletId}/receive-address") {
                val walletId = call.parameters["walletId"] ?: error("walletId missing")
                val addr = call.application.deps.explorer.getReceiveAddress(walletId)
                call.respond(addr)
            }
            
            // GET /api/v1/explorer/tx/{txid}
            get("/tx/{txid}") {
                val txid = call.parameters["txid"] ?: error("txid missing")
                val walletId = call.request.queryParameters["walletId"]
                val detail = call.application.deps.explorer.getTransactionDetail(txid, walletId)
                call.respond(detail)
            }
            
            // GET /api/v1/explorer/fees
            get("/fees") {
                val fees = call.application.deps.explorer.getFeeEstimates()
                call.respond(fees)
            }
        }
    }
}
