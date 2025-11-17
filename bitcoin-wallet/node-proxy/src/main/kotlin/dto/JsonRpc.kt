package org.example.nodeproxy.dto

data class JsonRpcRequest(
    val jsonrpc: String? = "2.0",
    val method: String,
    val params: Any? = emptyList<Any>(),
    val id: Any? = 1
)

data class JsonRpcError(val code: Int, val message: String)

data class JsonRpcResponse<T>(
    val jsonrpc: String = "2.0",
    val result: T? = null,
    val error: JsonRpcError? = null,
    val id: Any? = 1
)

object Allowlist {
    private val read = setOf(
        "getblockchaininfo", "getblockhash", "getblock",
        "getrawtransaction", "estimatesmartfee"
    )
    private val write = setOf(
        "finalizepsbt", "testmempoolaccept", "sendrawtransaction"
    )
    fun isAllowed(m: String) = m.lowercase() in read || m.lowercase() in write
}
