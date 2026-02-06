package cz.majny.wallet.gateway.dto

import kotlinx.serialization.Serializable

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: List<@Serializable(with = AnySerializer::class) Any?> = emptyList(),
    val id: @Serializable(with = AnySerializer::class) Any? = 1
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val result: @Serializable(with = AnySerializer::class) Any? = null,
    val error: JsonRpcError? = null,
    val id: @Serializable(with = AnySerializer::class) Any? = 1
)

@Serializable
data class BroadcastTxRequest(
    val hex: String
)

@Serializable
data class TestTxRequest(
    val hex: String
)

@Serializable
data class HealthResponse(
    val status: String,
    val ts: String
)
