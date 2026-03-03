package cz.majny.wallet.psbt.client

import cz.majny.wallet.psbt.api.UtxoDto
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Klient pro komunikaci s blockchain-service.
 */
class BlockchainClient(private val baseUrl: String) {
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
    
    /**
     * Získá UTXOs pro adresu.
     */
    suspend fun getUtxos(address: String, network: String = "mainnet"): List<UtxoDto> {
        return client.get("$baseUrl/api/v1/blockchain/address/$address/utxos") {
            parameter("network", network)
        }.body()
    }

    /**
     * Získá raw hex transakce — potřebné pro PSBT_IN_NON_WITNESS_UTXO.
     * Trezor firmware 2.4+ vyžaduje celou předchozí transakci pro všechny vstupy.
     */
    suspend fun getRawTransaction(txid: String, network: String = "mainnet"): RawTxResponse {
        return client.get("$baseUrl/api/v1/blockchain/tx/$txid/hex") {
            parameter("network", network)
        }.body()
    }

    /**
     * Broadcast raw transakce.
     */
    suspend fun broadcastTransaction(txHex: String, network: String = "mainnet"): BroadcastResult {
        val response = client.post("$baseUrl/api/v1/blockchain/tx/broadcast") {
            parameter("network", network)
            contentType(ContentType.Application.Json)
            setBody(BroadcastRequest(hex = txHex))
        }
        return response.body()
    }

    /**
     * Získá doporučené fee rates.
     */
    suspend fun getFeeEstimates(network: String = "mainnet"): FeeEstimates {
        return client.get("$baseUrl/api/v1/blockchain/fees") {
            parameter("network", network)
        }.body()
    }
}

@Serializable
data class RawTxResponse(
    val hex: String
)

@Serializable
data class BroadcastRequest(
    val hex: String
)

@Serializable
data class BroadcastResult(
    val txid: String? = null,
    val error: String? = null
)

@Serializable
data class FeeEstimates(
    val fastestFee: Int,
    val halfHourFee: Int,
    val hourFee: Int,
    val economyFee: Int,
    val minimumFee: Int
)
