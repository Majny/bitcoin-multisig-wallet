package cz.majny.wallet.gateway.http

import cz.majny.wallet.gateway.deps
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/* Wallet-membership guard for explorer routes. Resolves the caller's device_id
 * from the JWT, looks up the wallet in registry, and responds with 401/403/404
 * when the check fails. Returns true iff the caller may read this wallet. */
private suspend fun io.ktor.server.routing.RoutingContext.ensureWalletAccess(walletId: String): Boolean {
    val principal = call.principal<JWTPrincipal>()
    if (principal == null) {
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing JWT"))
        return false
    }
    val deviceId = principal.payload.getClaim("device_id").asString()
    return try {
        val wallet = call.application.deps.registry.getWallet(walletId)
        val hasAccess = wallet.members.any { it.deviceId == deviceId }
        if (!hasAccess) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied to wallet $walletId"))
        }
        hasAccess
    } catch (e: Exception) {
        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Wallet not found"))
        false
    }
}

fun Route.explorerRoutes() {
    authenticate("auth-jwt") {

        /* GET /api/v1/wallets/{walletId}/utxos — legacy path kept for older
         * clients. New callers should use /explorer/wallet/{id}/utxos. */
        get("/wallets/{walletId}/utxos") {
            val walletId = call.parameters["walletId"] ?: error("walletId missing")
            if (!ensureWalletAccess(walletId)) return@get
            val utxos = call.application.deps.explorer.getUtxos(walletId)
            call.respond(utxos)
        }

        route("/explorer") {

            /* GET /api/v1/explorer/wallet/{walletId}/balance — aggregated balance. */
            get("/wallet/{walletId}/balance") {
                val walletId = call.parameters["walletId"] ?: error("walletId missing")
                if (!ensureWalletAccess(walletId)) return@get
                val balance = call.application.deps.explorer.getWalletBalance(walletId)
                call.respond(balance)
            }

            /* GET /api/v1/explorer/wallet/{walletId}/transactions — paginated
             * tx history already classified as SENT / RECEIVED by explorer. */
            get("/wallet/{walletId}/transactions") {
                val walletId = call.parameters["walletId"] ?: error("walletId missing")
                if (!ensureWalletAccess(walletId)) return@get
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()
                val txs = call.application.deps.explorer.getWalletTransactions(walletId, limit, offset)
                call.respond(txs)
            }

            /* GET /api/v1/explorer/wallet/{walletId}/utxos — full UTXO list
             * (coin-control view) with derivation info per UTXO. */
            get("/wallet/{walletId}/utxos") {
                val walletId = call.parameters["walletId"] ?: error("walletId missing")
                if (!ensureWalletAccess(walletId)) return@get
                val utxos = call.application.deps.explorer.getWalletUtxos(walletId)
                call.respond(utxos)
            }

            /* GET /api/v1/explorer/wallet/{walletId}/receive-address — first
             * unused receive address, derives more if the gap limit is hit. */
            get("/wallet/{walletId}/receive-address") {
                val walletId = call.parameters["walletId"] ?: error("walletId missing")
                if (!ensureWalletAccess(walletId)) return@get
                val addr = call.application.deps.explorer.getReceiveAddress(walletId)
                call.respond(addr)
            }

            /* GET /api/v1/explorer/tx/{txid} — tx detail with YOURS flags for
             * the supplied ?walletId. Not wallet-membership guarded because the
             * underlying data is public on-chain. */
            get("/tx/{txid}") {
                val txid = call.parameters["txid"] ?: error("txid missing")
                val walletId = call.request.queryParameters["walletId"]
                val detail = call.application.deps.explorer.getTransactionDetail(txid, walletId)
                call.respond(detail)
            }

            /* GET /api/v1/explorer/fees — current sat/vB recommendations. */
            get("/fees") {
                val fees = call.application.deps.explorer.getFeeEstimates()
                call.respond(fees)
            }
        }
    }
}
