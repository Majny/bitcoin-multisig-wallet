package cz.majny.wallet.registry

import cz.majny.wallet.registry.api.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRegistryRoutes(repo: RegistryRepository) {

    routing {
        get("/health") { call.respondText("ok") }

        route("/registry") {

            post("/devices") {
                val req = call.receive<UpsertDeviceRequest>()
                val out = repo.upsertDevice(req)
                call.respond(out)
            }

            post("/wallets") {
                try {
                    val req = call.receive<CreateWalletRequest>()
                    val created = repo.createWallet(req)
                    call.respond(created)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad request"))
                }
            }

            get("/wallets") {
                val deviceId = call.request.queryParameters["device_id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing device_id"))
                call.respond(repo.listWalletsForDevice(deviceId))
            }

            get("/wallets/{id}") {
                val id = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing id"))
                val w = repo.getWallet(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("not found"))
                call.respond(w)
            }

            post("/wallets/{id}/members/attach") {
                val id = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing id"))
                val req = call.receive<MemberAttach>()
                repo.attachMember(id, req.deviceId, req.cosignerIdx)
                call.respondText("ok")
            }
        }
    }
}
