package org.example.nodeproxy

import zio.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.jackson.*
import org.example.nodeproxy.dto.JsonRpcRequest
import java.time.Instant

fun main() {
    val port = (System.getenv("PORT") ?: "8088").toInt()

    embeddedServer(Netty, port = port) {
        install(ContentNegotiation) { jackson() }

        // jednoduchý API key (volitelný)
        intercept(ApplicationCallPipeline.Setup) {
            RpcClient.requireApiKeyOrNull()?.let { expected ->
                val got = call.request.headers["X-API-Key"]
                if (got != expected) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing/invalid API key"))
                    finish()
                    return@intercept
                }
            }
        }

        routing {
            post("/rpc") {
                val req = call.receive<JsonRpcRequest>()
                val out = RpcClient.call(req)
                call.respond(HttpStatusCode.OK, out)
            }

            post("/tx/test") {
                data class TxReq(val hex: String, val id: Any? = 1)
                val b = call.receive<TxReq>()
                val out = RpcClient.call(
                    JsonRpcRequest(method = "testmempoolaccept", params = listOf(listOf(b.hex)), id = b.id)
                )
                call.respond(out)
            }

            post("/tx/broadcast") {
                data class TxReq(val hex: String, val id: Any? = 1)
                val b = call.receive<TxReq>()
                val out = RpcClient.call(
                    JsonRpcRequest(method = "sendrawtransaction", params = listOf(b.hex), id = b.id)
                )
                call.respond(out)
            }

            get("/healthz") { call.respond(mapOf("status" to "ok", "ts" to Instant.now().toString())) }
        }
    }.start(wait = true)
}
