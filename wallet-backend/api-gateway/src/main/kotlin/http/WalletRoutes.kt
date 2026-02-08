package cz.majny.wallet.gateway.http

import cz.majny.wallet.gateway.deps
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.walletRoutes() {
    authenticate("auth-jwt") {
        get("/wallets") {
            val principal = call.principal<JWTPrincipal>() ?: error("JWT principal missing")
            val deviceId = principal.payload.getClaim("device_id").asString()
            val wallets = call.application.deps.registry.listWallets(deviceId)
            call.respond(wallets)
        }
        
        /**
         * GET /wallets/{walletId}/address
         * 
         * Returns one or more derived addresses for the wallet.
         * Proxies to wallet-registry which stores pre-derived addresses.
         * 
         * Query params:
         * - type:  "receive" (default) or "change"
         * - index: address index (default 0). If omitted together with type, returns all addresses.
         */
        get("/wallets/{walletId}/address") {
            val principal = call.principal<JWTPrincipal>() ?: error("JWT principal missing")
            val deviceId = principal.payload.getClaim("device_id").asString()
            val walletId = call.parameters["walletId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing walletId"))
            
            val type = call.request.queryParameters["type"] ?: "receive"
            val index = call.request.queryParameters["index"]?.toIntOrNull() ?: 0
            
            // Verify user has access to this wallet
            val wallet = try {
                call.application.deps.registry.getWallet(walletId)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "wallet not found"))
                return@get
            }
            
            val hasAccess = wallet.members.any { it.deviceId == deviceId }
            if (!hasAccess) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "access denied"))
                return@get
            }
            
            // Proxy to wallet-registry
            val addressResponse = try {
                call.application.deps.registry.getWalletAddress(walletId, type, index)
            } catch (e: Exception) {
                call.application.log.error("Failed to get address from registry: ${e.message}", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "failed to get address: ${e.message}"))
                return@get
            }
            
            call.respond(addressResponse)
        }
    }
}
