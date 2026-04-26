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

        /* GET /api/v1/wallets — wallets the caller is a member of. */
        get("/wallets") {
            val principal = call.principal<JWTPrincipal>() ?: error("JWT principal missing")
            val deviceId = principal.payload.getClaim("device_id").asString()
            val wallets = call.application.deps.registry.listWallets(deviceId)
            call.application.log.info("GET /wallets for device={}: {} wallets, types={}",
                deviceId, wallets.size, wallets.map { "${it.walletId}(${it.type})" })
            call.respond(wallets)
        }

        /* POST /api/v1/wallets — creates a wallet (single-sig or multisig).
         * The request body is forwarded to wallet-registry after ensuring the
         * caller is listed as a member so they get access immediately. */
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

        /* POST /api/v1/wallets/import — imports a wallet from an output
         * descriptor (typically produced in Sparrow). Gateway enriches the
         * registry request with deviceId + fingerprint from the JWT so the
         * registry knows who the caller is. */
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

        /* GET /api/v1/wallets/{walletId}/address — one derived address from the
         * registry's pre-derived pool. Query params: type (receive/change,
         * default receive), index (default 0). Enforces membership before
         * proxying. */
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

        /* PUT /api/v1/wallets/{walletId}/cosigners/{idx}/label — upserts the
         * caller's own label for a cosigner position. Scoped by device_id from
         * the JWT: labels are private to each Trezor and never leak between
         * members of the same multisig wallet. */
        put("/wallets/{walletId}/cosigners/{idx}/label") {
            val principal = call.principal<JWTPrincipal>() ?: error("JWT principal missing")
            val deviceId = principal.payload.getClaim("device_id").asString()
            val walletId = call.parameters["walletId"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing walletId"))
            val idx = call.parameters["idx"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid idx"))
            val body = call.receive<Map<String, String>>()
            val label = body["label"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing label"))

            // Verify the caller is actually a member of this wallet.
            val wallet = try {
                call.application.deps.registry.getWallet(walletId)
            } catch (e: Exception) {
                return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "wallet not found"))
            }
            if (wallet.members.none { it.deviceId == deviceId }) {
                return@put call.respond(HttpStatusCode.Forbidden, mapOf("error" to "access denied"))
            }

            call.application.deps.registry.updateCosignerLabel(walletId, idx, label, deviceId)
            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        }
    }
}
