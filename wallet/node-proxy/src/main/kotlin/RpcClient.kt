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
import org.example.nodeproxy.dto.Allowlist
import org.example.nodeproxy.dto.JsonRpcError
import org.example.nodeproxy.dto.JsonRpcRequest
import org.example.nodeproxy.dto.JsonRpcResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

data class Config(
    val rpcUrl: String,
    val rpcUser: String?,
    val rpcPass: String?,
    val apiKey: String?,
    val connectTimeoutMs: Long = 4_000,
    val socketTimeoutMs: Long = 8_000,
    val retries: Int = 2
)

private val env = runCatching { dotenv() }.getOrNull()

private fun need(k: String) = env?.get(k) ?: System.getenv(k) ?: error("Missing env $k")
private fun opt(k: String) = env?.get(k) ?: System.getenv(k)

private fun readCookieAuthOrBasic(user: String?, pass: String?): String {
    if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
        return "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
    }
    // TODO: cookie fallback: ~/.bitcoin/.cookie
    val cookiePath = Path.of(System.getProperty("user.home"), ".bitcoin", ".cookie")
    val cookie = Files.readString(cookiePath).trim()
    return "Basic " + Base64.getEncoder().encodeToString(cookie.toByteArray())
}

object RpcClient {
    private val cfg = Config(
        rpcUrl = need("RPC_URL"),          // na RPi: http://127.0.0.1:8332/
        rpcUser = opt("RPC_USER"),         // if missing -> cookie fallback
        rpcPass = opt("RPC_PASS"),
        apiKey = opt("API_KEY"),
        connectTimeoutMs = (opt("UPSTREAM_CONNECT_TIMEOUT_MS") ?: "4000").toLong(),
        socketTimeoutMs = (opt("UPSTREAM_SOCKET_TIMEOUT_MS")  ?: "8000").toLong(),
        retries = (opt("UPSTREAM_RETRIES") ?: "2").toInt()
    )

    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)

    private val authHeader = readCookieAuthOrBasic(cfg.rpcUser, cfg.rpcPass)

    private val http = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = cfg.socketTimeoutMs
            connectTimeoutMillis = cfg.connectTimeoutMs
            socketTimeoutMillis  = cfg.socketTimeoutMs
        }
        engine {
            pipelining = false
            maxConnectionsCount = 100
        }
        defaultRequest {
            url(cfg.rpcUrl)
            header(HttpHeaders.Authorization, authHeader)
            contentType(ContentType.Text.Plain)
            accept(ContentType.Application.Json)
        }
    }

    private val windowMs = 1.minutes.inWholeMilliseconds
    private val limitPerWindow = 60
    private val counters = ConcurrentHashMap<String, Pair<Long, AtomicInteger>>() // key -> (windowStart, count)

    fun requireApiKeyOrNull(): String? = cfg.apiKey

    fun allowRequest(bucketKey: String): Boolean {
        val now = System.currentTimeMillis()
        val entry = counters.compute(bucketKey) { _, old ->
            val winStart = old?.first ?: now
            val cnt = old?.second ?: AtomicInteger(0)
            if (now - winStart >= windowMs) {
                Pair(now, AtomicInteger(1))
            } else {
                if (cnt.incrementAndGet() <= limitPerWindow) Pair(winStart, cnt)
                else Pair(winStart, cnt)
            }
        }!!
        val (start, count) = entry
        return if (now - start >= windowMs) true else count.get() <= limitPerWindow
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

                runCatching { return mapper.readValue<JsonRpcResponse<Any?>>(text) }.onFailure {
                    if (r.status.isSuccess()) {
                        return JsonRpcResponse(error = JsonRpcError(-32000, "Invalid upstream JSON"), id = req.id)
                    }
                }

                if (!r.status.isSuccess()) {
                    return JsonRpcResponse(error = JsonRpcError(r.status.value, "Upstream HTTP ${r.status.value}"), id = req.id)
                }
            } catch (t: Throwable) {
                last = t
                if (attempt < cfg.retries) Thread.sleep(150L * (attempt + 1))
            }
        }
        return JsonRpcResponse(error = JsonRpcError(-32000, "Upstream error: ${last?.message ?: "unknown"}"), id = req.id)
    }
}
