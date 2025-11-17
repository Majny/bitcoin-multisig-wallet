package org.example.nodeproxy

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.jackson.*
import io.ktor.server.plugins.callloging.CallLogging
import org.example.nodeproxy.dto.JsonRpcRequest
import java.time.Instant

fun main() {
    val port = (System.getenv("PORT") ?: "8088").toInt()
    val host = System.getenv("BIND_HOST") ?: "0.0.0.0"
    embeddedServer(Netty, host = host, port = port) { module() }.start(wait = true)
}

fun Application.module() {
    install(CallLogging)
    install(ContentNegotiation) { jackson() }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            // Parse error / invalid JSON body
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "jsonrpc" to "2.0",
                    "error" to mapOf("code" to -32700, "message" to (cause.message ?: "Parse error")),
                    "id" to null
                )
            )
        }
    }

    // API key guard + rate-limit
    intercept(ApplicationCallPipeline.Setup) {
        RpcClient.requireApiKeyOrNull()?.let { expected ->
            val got = call.request.headers["X-API-Key"]
            if (got != expected) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing/invalid API key"))
                finish(); return@intercept
            }
            val bucket = "key:$got"
            if (!RpcClient.allowRequest(bucket)) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Rate limit exceeded"))
                finish(); return@intercept
            }
        }
    }

    routing {
        // 1) JSON-RPC passthrough
        post("/rpc") {
            val req = call.receive<JsonRpcRequest>()
            val out = RpcClient.call(req)
            call.respond(HttpStatusCode.OK, out)
        }

        // 2) TX endpoints
        post("/tx/test") {
            data class TxReq(val hex: String, val id: Any? = 1)
            val b = call.receive<TxReq>()
            require(b.hex.length <= 1_000_000) { "tx too large" } // ~500 kB raw
            val out = RpcClient.call(
                JsonRpcRequest(method = "testmempoolaccept", params = listOf(listOf(b.hex)), id = b.id)
            )
            call.respond(out)
        }

        post("/tx/broadcast") {
            data class TxReq(val hex: String, val id: Any? = 1)
            val b = call.receive<TxReq>()
            require(b.hex.length <= 1_000_000) { "tx too large" }
            val out = RpcClient.call(
                JsonRpcRequest(method = "sendrawtransaction", params = listOf(b.hex), id = b.id)
            )
            call.respond(out)
        }

        // 3) Health
        get("/healthz") {
            call.respond(mapOf("status" to "ok", "ts" to Instant.now().toString()))
        }
    }
}
