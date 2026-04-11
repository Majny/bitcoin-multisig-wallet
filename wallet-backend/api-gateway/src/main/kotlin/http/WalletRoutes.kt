package cz.majny.wallet.gateway.http

import cz.majny.wallet.gateway.deps
import cz.majny.wallet.gateway.dto.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.walletRoutes() {
    authenticate("auth-jwt") {
        get("/wallets") {
            val principal = call.principal<JWTPrincipal>() ?: error("JWT principal missing")
            val deviceId = principal.payload.getClaim("device_id").asString()
            val wallets = call.application.deps.registry.listWallets(deviceId)
            call.application.log.info("GET /wallets for device={}: {} wallets, types={}",
                deviceId, wallets.size, wallets.map { "${it.walletId}(${it.type})" })
            call.respond(wallets)
        }

        /**
         * POST /api/v1/wallets
         *
         * Create a new wallet (single-sig or multisig).
         * The request body is forwarded directly to wallet-registry.
         */
        post("/wallets") {
            val principal = call.principal<JWTPrincipal>() ?: error("JWT principal missing")
            val deviceId = principal.payload.getClaim("device_id").asString()
            val fingerprint = principal.payload.getClaim("fingerprint").asString()

            val req = call.receive<CreateWalletRequest>()

            // Ensure the calling device is in the members list
            val membersWithDevice = if (req.members.none { it.deviceId == deviceId }) {
                req.members + MemberAttach(deviceId = deviceId)
            } else {
                req.members
            }

            val enriched = req.copy(members = membersWithDevice)

            try {
                val created = call.application.deps.registry.createWallet(enriched)
                call.respond(HttpStatusCode.Created, created)
            } catch (e: Exception) {
                call.application.log.error("Failed to create wallet: ${e.message}", e)
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "create failed")))
            }
        }

        /**
         * POST /api/v1/wallets/import
         *
         * Import a wallet from an output descriptor (e.g. from Sparrow, Bitcoin Core).
         * Supports both single-sig and multisig descriptors.
         *
         * Body: { descriptor, network?, label?, birthHeight? }
         * The gateway enriches with deviceId and fingerprint from JWT.
         */
        post("/wallets/import") {
            call.application.log.info("POST /wallets/import received")
            val principal = call.principal<JWTPrincipal>() ?: error("JWT principal missing")
            val deviceId = principal.payload.getClaim("device_id").asString()
            val fingerprint = principal.payload.getClaim("fingerprint").asString()
            call.application.log.info("Import: deviceId={}, fingerprint={}", deviceId, fingerprint)

            val appReq = call.receive<ImportWalletFromAppRequest>()
            call.application.log.info("Import body: network={}, accountIndex={}, descriptor={}...",
                appReq.network, appReq.accountIndex, appReq.descriptor.take(60))

            if (appReq.descriptor.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "descriptor is required"))
                return@post
            }

            // Enrich with device info from JWT
            val registryReq = ImportWalletGatewayRequest(
                descriptor = appReq.descriptor,
                network = appReq.network,
                label = appReq.label,
                birthHeight = appReq.birthHeight,
                deviceId = deviceId,
                deviceFingerprint = fingerprint,
                accountIndex = appReq.accountIndex
            )

            try {
                val result = call.application.deps.registry.importWallet(registryReq)
                if (result.success) {
                    val status = if (result.isNew) HttpStatusCode.Created else HttpStatusCode.OK
                    call.respond(status, result)
                } else {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (result.error ?: "import failed")))
                }
            } catch (e: Exception) {
                call.application.log.error("Failed to import wallet: ${e.message}", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "import error")))
            }
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

        /**
         * PUT /wallets/{walletId}/cosigners/{idx}/label
         * Updates the display label for a cosigner in a multisig wallet.
         */
        put("/wallets/{walletId}/cosigners/{idx}/label") {
            val walletId = call.parameters["walletId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing walletId"))
            val idx = call.parameters["idx"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid idx"))
            val body = call.receive<Map<String, String>>()
            val label = body["label"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing label"))
            call.application.deps.registry.updateCosignerLabel(walletId, idx, label)
            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        }
    }
}
