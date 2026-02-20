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
    suspend fun getUtxos(address: String): List<UtxoDto> {
        return client.get("$baseUrl/api/v1/blockchain/address/$address/utxos").body()
    }
    
    /**
     * Získá raw transakci podle txid.
     */
    suspend fun getRawTransaction(txid: String): RawTxResponse {
        return client.get("$baseUrl/api/v1/blockchain/tx/$txid/hex").body()
    }
    
    /**
     * Broadcast raw transakce.
     */
    suspend fun broadcastTransaction(txHex: String): BroadcastResult {
        val response = client.post("$baseUrl/api/v1/blockchain/tx/broadcast") {
            contentType(ContentType.Application.Json)
            setBody(BroadcastRequest(hex = txHex))
        }
        return response.body()
    }
    
    /**
     * Získá doporučené fee rates.
     */
    suspend fun getFeeEstimates(): FeeEstimates {
        return client.get("$baseUrl/api/v1/blockchain/fees").body()
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
