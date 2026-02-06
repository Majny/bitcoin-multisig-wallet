package cz.majny.wallet.gateway.clients

import cz.majny.wallet.gateway.config.AppConfig
import cz.majny.wallet.gateway.dto.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

interface NodeProxyClient {
    /**
     * Generic JSON-RPC call to Bitcoin Core via Node Proxy.
     * Only allowlisted methods will be accepted by Node Proxy.
     */
    suspend fun rpcCall(request: JsonRpcRequest): JsonRpcResponse

    /**
     * Test if a raw transaction would be accepted by the mempool.
     * Calls `testmempoolaccept` RPC method.
     */
    suspend fun testTransaction(hex: String): JsonRpcResponse

    /**
     * Broadcast a signed raw transaction to the Bitcoin network.
     * Calls `sendrawtransaction` RPC method.
     */
    suspend fun broadcastTransaction(hex: String): JsonRpcResponse

    /**
     * Health check for Node Proxy service.
     */
    suspend fun health(): HealthResponse
}

class NodeProxyClientImpl(private val cfg: AppConfig) : NodeProxyClient {

    private lateinit var client: HttpClient

    fun attach(http: HttpClient) {
        client = http
    }

    private fun requireClient(): HttpClient {
        check(this::client.isInitialized) {
            "NodeProxyClientImpl is not attached. Call deps.attachHttpClients(application) first."
        }
        return client
    }

    override suspend fun rpcCall(request: JsonRpcRequest): JsonRpcResponse {
        val http = requireClient()

        val resp = upstreamRequest("node-proxy") {
            http.post("${cfg.nodeProxyBaseUrl}/rpc") {
                contentType(ContentType.Application.Json)
                cfg.nodeProxyApiKey?.let { header("X-API-Key", it) }
                setBody(request)
            }
        }.ensureSuccess("node-proxy")

        return resp.body()
    }

    override suspend fun testTransaction(hex: String): JsonRpcResponse {
        val http = requireClient()

        val resp = upstreamRequest("node-proxy") {
            http.post("${cfg.nodeProxyBaseUrl}/tx/test") {
                contentType(ContentType.Application.Json)
                cfg.nodeProxyApiKey?.let { header("X-API-Key", it) }
                setBody(TestTxRequest(hex = hex))
            }
        }.ensureSuccess("node-proxy")

        return resp.body()
    }

    override suspend fun broadcastTransaction(hex: String): JsonRpcResponse {
        val http = requireClient()

        val resp = upstreamRequest("node-proxy") {
            http.post("${cfg.nodeProxyBaseUrl}/tx/broadcast") {
                contentType(ContentType.Application.Json)
                cfg.nodeProxyApiKey?.let { header("X-API-Key", it) }
                setBody(BroadcastTxRequest(hex = hex))
            }
        }.ensureSuccess("node-proxy")

        return resp.body()
    }

    override suspend fun health(): HealthResponse {
        val http = requireClient()

        val resp = upstreamRequest("node-proxy") {
            http.get("${cfg.nodeProxyBaseUrl}/healthz") {
                cfg.nodeProxyApiKey?.let { header("X-API-Key", it) }
            }
        }.ensureSuccess("node-proxy")

        return resp.body()
    }
}
