package cz.majny.wallet.gateway.http

import cz.majny.wallet.gateway.deps
import cz.majny.wallet.gateway.utils.AddressDerivation
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

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
         * Returns the current receive address for the wallet.
         * Derives address from the wallet's receive descriptor.
         * 
         * Query params:
         * - index: Address index (default 0)
         */
        get("/wallets/{walletId}/address") {
            val principal = call.principal<JWTPrincipal>() ?: error("JWT principal missing")
            val deviceId = principal.payload.getClaim("device_id").asString()
            val walletId = call.parameters["walletId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing walletId"))
            val index = call.request.queryParameters["index"]?.toIntOrNull() ?: 0
            
            // Get wallet detail from registry
            val wallet = call.application.deps.registry.getWallet(walletId)
            if (wallet == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "wallet not found"))
                return@get
            }
            
            // Verify user has access to this wallet
            val hasAccess = wallet.members.any { it.deviceId == deviceId }
            if (!hasAccess) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "access denied"))
                return@get
            }
            
            // Derive address from descriptor using BitcoinJ
            val address = try {
                AddressDerivation.deriveAddress(wallet.receiveDescriptor, index, wallet.network)
            } catch (e: Exception) {
                call.application.log.error("Failed to derive address from descriptor: ${e.message}", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "failed to derive address: ${e.message}"))
                return@get
            }
            
            call.respond(WalletAddressResponse(
                walletId = walletId,
                address = address,
                index = index,
                type = "receive"
            ))
        }
    }
}

@Serializable
data class WalletAddressResponse(
    val walletId: String,
    val address: String,
    val index: Int,
    val type: String
)
