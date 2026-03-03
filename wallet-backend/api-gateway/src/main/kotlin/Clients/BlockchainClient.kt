package cz.majny.wallet.gateway.clients

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

/**
 * Client for blockchain-service microservice.
 * Proxies requests to the dedicated blockchain service.
 */
interface BlockchainClient {
    suspend fun getAddressInfo(address: String, network: String = "mainnet"): AddressInfoResponse
    suspend fun getAddressUtxos(address: String, network: String = "mainnet"): List<UtxoResponse>
    suspend fun getAddressTransactions(address: String, network: String = "mainnet"): List<TransactionResponse>
    suspend fun hasActivity(address: String, network: String = "mainnet"): HasActivityResponse
    suspend fun getFeeEstimates(network: String = "mainnet"): FeeEstimatesResponse
    suspend fun getTransaction(txid: String, network: String = "mainnet"): TransactionResponse
    suspend fun broadcastTransaction(hex: String, network: String = "mainnet"): BroadcastResponse
}

// ============ Response DTOs ============

@Serializable
data class AddressInfoResponse(
    val address: String,
    val txCount: Int,
    val balance: Long
)

@Serializable
data class UtxoResponse(
    val txid: String,
    val vout: Int,
    val value: Long,
    val status: UtxoStatusResponse
)

@Serializable
data class UtxoStatusResponse(
    val confirmed: Boolean,
    val block_height: Int? = null,
    val block_hash: String? = null,
    val block_time: Long? = null
)

@Serializable
data class TransactionResponse(
    val txid: String,
    val version: Int = 2,
    val locktime: Int = 0,
    val size: Int = 0,
    val weight: Int = 0,
    val fee: Long = 0,
    val status: TxStatusResponse = TxStatusResponse()
)

@Serializable
data class TxStatusResponse(
    val confirmed: Boolean = false,
    val block_height: Int? = null,
    val block_hash: String? = null,
    val block_time: Long? = null
)

@Serializable
data class HasActivityResponse(
    val address: String,
    val hasActivity: Boolean
)

@Serializable
data class FeeEstimatesResponse(
    val fastestFee: Int,
    val halfHourFee: Int,
    val hourFee: Int,
    val economyFee: Int,
    val minimumFee: Int
)

@Serializable
data class BroadcastResponse(
    val success: Boolean,
    val txid: String?,
    val error: String? = null
)

@Serializable
data class BroadcastRequest(
    val hex: String
)

// ============ Implementation ============

class BlockchainClientImpl(
    private val baseUrl: String
) : BlockchainClient {

    private lateinit var client: HttpClient

    fun attach(http: HttpClient) {
        client = http
    }

    private fun requireClient(): HttpClient {
        check(this::client.isInitialized) {
            "BlockchainClientImpl is not attached. Call deps.attachHttpClients(application) first."
        }
        return client
    }

    override suspend fun getAddressInfo(address: String, network: String): AddressInfoResponse {
        return requireClient().get("$baseUrl/api/v1/blockchain/address/$address") {
            parameter("network", network)
        }.body()
    }

    override suspend fun getAddressUtxos(address: String, network: String): List<UtxoResponse> {
        return requireClient().get("$baseUrl/api/v1/blockchain/address/$address/utxos") {
            parameter("network", network)
        }.body()
    }

    override suspend fun getAddressTransactions(address: String, network: String): List<TransactionResponse> {
        return requireClient().get("$baseUrl/api/v1/blockchain/address/$address/txs") {
            parameter("network", network)
        }.body()
    }

    override suspend fun hasActivity(address: String, network: String): HasActivityResponse {
        return requireClient().get("$baseUrl/api/v1/blockchain/address/$address/has-activity") {
            parameter("network", network)
        }.body()
    }

    override suspend fun getFeeEstimates(network: String): FeeEstimatesResponse {
        return requireClient().get("$baseUrl/api/v1/blockchain/fees") {
            parameter("network", network)
        }.body()
    }

    override suspend fun getTransaction(txid: String, network: String): TransactionResponse {
        return requireClient().get("$baseUrl/api/v1/blockchain/tx/$txid") {
            parameter("network", network)
        }.body()
    }

    override suspend fun broadcastTransaction(hex: String, network: String): BroadcastResponse {
        return requireClient().post("$baseUrl/api/v1/blockchain/tx/broadcast") {
            contentType(ContentType.Application.Json)
            parameter("network", network)
            setBody(BroadcastRequest(hex))
        }.body()
    }
}
