package org.example.nodeproxy

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.example.nodeproxy.dto.*
import java.util.Base64

data class Config(
    val rpcUrl: String,
    val rpcUser: String,
    val rpcPass: String,
    val apiKey: String?,
    val connectTimeoutMs: Long = 4_000,
    val socketTimeoutMs: Long = 8_000,
    val retries: Int = 2
)

private val env = dotenv() // načte node-proxy/.env automaticky

private fun need(k: String) = env[k] ?: System.getenv(k) ?: error("Missing env $k")
private fun opt(k: String) = env[k] ?: System.getenv(k)

object RpcClient {
    private val cfg = Config(
        rpcUrl = need("RPC_URL"),
        rpcUser = need("RPC_USER"),
        rpcPass = need("RPC_PASS"),
        apiKey  = opt("API_KEY"),
        connectTimeoutMs = (opt("UPSTREAM_CONNECT_TIMEOUT_MS") ?: "4000").toLong(),
        socketTimeoutMs  = (opt("UPSTREAM_SOCKET_TIMEOUT_MS")  ?: "8000").toLong(),
        retries          = (opt("UPSTREAM_RETRIES") ?: "2").toInt()
    )

    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)

    private val authHeader = "Basic " + Base64.getEncoder()
        .encodeToString("${cfg.rpcUser}:${cfg.rpcPass}".toByteArray())

    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = cfg.socketTimeoutMs
            connectTimeoutMillis = cfg.connectTimeoutMs
            socketTimeoutMillis  = cfg.socketTimeoutMs
        }
        engine {
            pipelining = false
            maxConnectionsCount = 50
            endpoint {
                keepAliveTime = 0
                requestTimeout = cfg.socketTimeoutMs.toInt()
                connectTimeout = cfg.connectTimeoutMs.toInt()
            }
        }
        defaultRequest {
            url(cfg.rpcUrl) // Tailscale RPC endpoint
            header(HttpHeaders.Authorization, authHeader)
            header(HttpHeaders.Connection, "close")
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
        }
    }

    suspend fun call(req: JsonRpcRequest): JsonRpcResponse<Any?> {
        if (!Allowlist.isAllowed(req.method)) {
            return JsonRpcResponse(error = JsonRpcError(-32601, "Method not allowed: ${req.method}"), id = req.id)
        }
        val body = mapper.writeValueAsString(req)

        var last: Throwable? = null
        repeat(cfg.retries + 1) { attempt ->
            try {
                val r: HttpResponse = http.post { setBody(body) }
                val text = r.bodyAsText()
                return if (r.status.isSuccess()) mapper.readValue(text)
                else JsonRpcResponse(error = JsonRpcError(r.status.value, "Upstream HTTP ${r.status.value}"), id = req.id)
            } catch (t: Throwable) {
                last = t
                if (attempt < cfg.retries) Thread.sleep(150L * (attempt + 1))
            }
        }
        return JsonRpcResponse(error = JsonRpcError(-32000, "Upstream error: ${last?.message ?: "unknown"}"), id = req.id)
    }

    fun requireApiKeyOrNull(): String? = cfg.apiKey
}
