package cz.majny.wallet.registry

import cz.majny.wallet.registry.api.*
import cz.majny.wallet.registry.importer.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRoutes(repo: Repository) {

    val importer = WalletImporter(repo)

    routing {
        get("/health") { call.respondText("ok") }

        route("/registry") {

            /*
             * POST /registry/derive-addresses
             * Derives addresses from a descriptor without creating a wallet.
             * Called by api-gateway during login for account discovery.
             */
            post("/derive-addresses") {
                try {
                    val req = call.receive<DeriveAddressesRequest>()
                    val derived = AddressDerivation.deriveAddresses(
                        descriptor = req.descriptor,
                        network = req.network,
                        chain = 0,
                        fromIndex = 0,
                        count = req.count
                    )
                    call.respond(DeriveAddressesResponse(addresses = derived.map { it.address }))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "derivation failed"))
                }
            }

            /*
             * POST /registry/devices
             * Registers or updates a Trezor device. Called by api-gateway on every login.
             */
            post("/devices") {
                val req = call.receive<UpsertDeviceRequest>()
                val out = repo.upsertDevice(req)
                call.respond(out)
            }

            /*
             * POST /registry/wallets
             * Creates a new wallet (singlesig or multisig) with cosigners, members, and derived addresses.
             * Idempotent — returns existing wallet if wallet_id already exists.
             * Called by api-gateway during login (singlesig) or wallet creation.
             */
            post("/wallets") {
                try {
                    val req = call.receive<CreateWalletRequest>()
                    val created = repo.createWallet(req)
                    call.respond(created)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad request"))
                }
            }

            /*
             * POST /registry/wallets/import
             * Imports a wallet from an output descriptor string (e.g., from Sparrow Wallet).
             * Parses descriptor, creates wallet + cosigners, derives addresses.
             * If wallet already exists, auto-attaches the calling device as member.
             * Called by api-gateway when user imports a multisig descriptor.
             */
            post("/wallets/import") {
                val log = application.log
                log.info("POST /registry/wallets/import received")
                try {
                    val req = call.receive<ImportWalletRequest>()
                    log.info("Import request: network={}, deviceId={}, accountIndex={}, descriptor={}...",
                        req.network, req.deviceId, req.accountIndex, req.descriptor.take(60))

                    if (req.descriptor.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("descriptor is required"))
                        return@post
                    }

                    val result = importer.importWallet(req)
                    log.info("Import result: success={}, walletId={}, isNew={}, error={}",
                        result.success, result.walletId, result.isNew, result.error)

                    if (result.success) {
                        val status = if (result.isNew) HttpStatusCode.Created else HttpStatusCode.OK
                        call.respond(status, result)
                    } else {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.error ?: "import failed"))
                    }
                } catch (e: Exception) {
                    log.error("Import endpoint exception: {}", e.message, e)
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "invalid request"))
                }
            }

            /*
             * GET /registry/wallets?device_id=xxx
             * Lists all wallets accessible by the given device.
             * Called by api-gateway on login and dashboard load.
             */
            get("/wallets") {
                val deviceId = call.request.queryParameters["device_id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing device_id"))
                call.respond(repo.listWalletsForDevice(deviceId))
            }

            /*
             * GET /registry/wallets/{id}
             * Returns full wallet detail (cosigners, members, descriptors).
             * Called by api-gateway and psbt-service.
             */
            get("/wallets/{id}") {
                val id = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing id"))
                val w = repo.getWallet(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("not found"))
                call.respond(w)
            }

            /*
             * POST /registry/wallets/{id}/members/attach
             * Attaches a device as a member of an existing wallet.
             * Called by api-gateway during login for multisig wallets.
             */
            post("/wallets/{id}/members/attach") {
                val id = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing id"))
                val req = call.receive<MemberAttach>()
                repo.attachMember(id, req.deviceId, req.accountIndex)
                call.respondText("ok")
            }

            /*
             * GET /registry/wallets/{id}/addresses?type=receive&index=0
             * Returns pre-derived Bitcoin addresses for a wallet.
             * Called by api-gateway when frontend needs a receive address.
             */
            get("/wallets/{id}/addresses") {
                val id = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing id"))
                val type = call.request.queryParameters["type"]
                val index = call.request.queryParameters["index"]?.toIntOrNull()

                if (index != null) {
                    val addr = repo.getAddress(id, type ?: "receive", index)
                        ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("address not found"))
                    call.respond(addr)
                } else {
                    val addresses = repo.getAddresses(id, type)
                    call.respond(WalletAddressesResponse(walletId = id, addresses = addresses))
                }
            }
        }
    }
}
