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

/*
 * node-proxy entry point. Thin JSON-RPC facade in front of a Bitcoin Core
 * node: exposes only a curated allowlist of methods (see dto/AllowList.kt)
 * and layers a shared-secret X-API-Key check + per-key rate limit on top.
 *
 * Standalone Gradle module — deliberately not in the main settings.gradle
 * or docker-compose; it runs next to the Core node, not in the service
 * mesh.
 */
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
            // Return a JSON-RPC shaped error rather than a generic Ktor 400
            // — clients always expect the {jsonrpc, error, id} envelope.
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

    /*
     * Auth + rate-limit interceptor. Runs before routing; if API_KEY is
     * unset the whole thing is bypassed (useful for local dev against a
     * regtest node).
     */
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
        /* POST /rpc — generic JSON-RPC passthrough. The method is still
         * checked against the allowlist inside RpcClient.call. */
        post("/rpc") {
            val req = call.receive<JsonRpcRequest>()
            val out = RpcClient.call(req)
            call.respond(HttpStatusCode.OK, out)
        }

        /* POST /tx/test — convenience wrapper around testmempoolaccept.
         * Guards against absurdly large payloads up front so upstream
         * doesn't have to. */
        post("/tx/test") {
            data class TxReq(val hex: String, val id: Any? = 1)
            val b = call.receive<TxReq>()
            require(b.hex.length <= 1_000_000) { "tx too large" }
            val out = RpcClient.call(
                JsonRpcRequest(method = "testmempoolaccept", params = listOf(listOf(b.hex)), id = b.id)
            )
            call.respond(out)
        }

        /* POST /tx/broadcast — convenience wrapper around sendrawtransaction. */
        post("/tx/broadcast") {
            data class TxReq(val hex: String, val id: Any? = 1)
            val b = call.receive<TxReq>()
            require(b.hex.length <= 1_000_000) { "tx too large" }
            val out = RpcClient.call(
                JsonRpcRequest(method = "sendrawtransaction", params = listOf(b.hex), id = b.id)
            )
            call.respond(out)
        }

        /* GET /healthz — local liveness only. Intentionally does not touch
         * Core so orchestrators can still see the proxy as "up" during a
         * node restart. */
        get("/healthz") {
            call.respond(
                HttpStatusCode.OK,
                mapOf("status" to "ok", "ts" to Instant.now().toString())
            )
        }
    }
}
