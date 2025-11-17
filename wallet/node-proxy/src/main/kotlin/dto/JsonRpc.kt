package org.example.nodeproxy.dto

data class JsonRpcRequest(
    val jsonrpc: String? = "2.0",
    val method: String,
    val params: Any? = emptyList<Any?>(),
    val id: Any? = 1
)

data class JsonRpcError(val code: Int, val message: String)

data class JsonRpcResponse<T>(
    val jsonrpc: String = "2.0",
    val result: T? = null,
    val error: JsonRpcError? = null,
    val id: Any? = 1
)
