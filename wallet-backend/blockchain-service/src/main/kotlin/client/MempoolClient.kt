package cz.majny.wallet.blockchain.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

/**
 * Client for Mempool.space public API.
 * 
 * Mainnet: https://mempool.space/api/
 * Testnet: https://mempool.space/testnet/api/
 */
interface MempoolClient {
    
    /**
     * Get address info including tx_count for account discovery.
     */
    suspend fun getAddressInfo(address: String): AddressInfo
    
    /**
     * Get UTXOs for an address (for coin control).
     */
    suspend fun getAddressUtxos(address: String): List<MempoolUtxo>
    
    /**
     * Get transaction history for an address.
     */
    suspend fun getAddressTransactions(address: String): List<MempoolTransaction>
    
    /**
     * Get recommended fee rates.
     */
    suspend fun getFeeEstimates(): FeeEstimates
    
    /**
     * Get transaction details by txid.
     */
    suspend fun getTransaction(txid: String): MempoolTransaction
    
    /**
     * Broadcast a raw transaction hex.
     * Returns txid on success.
     */
    suspend fun broadcastTransaction(hex: String): String
    
    /**
     * Check if address has any activity (for account discovery).
     */
    suspend fun hasActivity(address: String): Boolean

    /**
     * Vrátí výšku aktuálního nejlepšího bloku (tip).
     * Používá se pro výpočet počtu konfirmací.
     */
    suspend fun getTipHeight(): Int
}

// ============ DTOs ============

@Serializable
data class AddressInfo(
    val address: String,
    val chain_stats: ChainStats,
    val mempool_stats: MempoolStats
) {
    val txCount: Int get() = chain_stats.tx_count + mempool_stats.tx_count
    val balance: Long get() = chain_stats.funded_txo_sum - chain_stats.spent_txo_sum +
            mempool_stats.funded_txo_sum - mempool_stats.spent_txo_sum
}

@Serializable
data class ChainStats(
    val funded_txo_count: Int = 0,
    val funded_txo_sum: Long = 0,
    val spent_txo_count: Int = 0,
    val spent_txo_sum: Long = 0,
    val tx_count: Int = 0
)

@Serializable
data class MempoolStats(
    val funded_txo_count: Int = 0,
    val funded_txo_sum: Long = 0,
    val spent_txo_count: Int = 0,
    val spent_txo_sum: Long = 0,
    val tx_count: Int = 0
)

@Serializable
data class MempoolUtxo(
    val txid: String,
    val vout: Int,
    val value: Long,
    val status: UtxoStatus
)

@Serializable
data class UtxoStatus(
    val confirmed: Boolean,
    val block_height: Int? = null,
    val block_hash: String? = null,
    val block_time: Long? = null
)

@Serializable
data class MempoolTransaction(
    val txid: String,
    val version: Int = 2,
    val locktime: Int = 0,
    val size: Int = 0,
    val weight: Int = 0,
    val fee: Long = 0,
    val vin: List<MempoolTxInput> = emptyList(),
    val vout: List<MempoolTxOutput> = emptyList(),
    val status: TxStatus = TxStatus()
)

@Serializable
data class MempoolTxInput(
    val txid: String = "",
    val vout: Int = 0,
    val prevout: MempoolTxPrevout? = null,
    val is_coinbase: Boolean = false,
    val sequence: Long = 0
)

@Serializable
data class MempoolTxPrevout(
    val scriptpubkey: String = "",
    val scriptpubkey_asm: String = "",
    val scriptpubkey_type: String = "",
    val scriptpubkey_address: String = "",
    val value: Long = 0
)

@Serializable
data class MempoolTxOutput(
    val scriptpubkey: String = "",
    val scriptpubkey_asm: String = "",
    val scriptpubkey_type: String = "",
    val scriptpubkey_address: String = "",
    val value: Long = 0
)

@Serializable
data class TxStatus(
    val confirmed: Boolean = false,
    val block_height: Int? = null,
    val block_hash: String? = null,
    val block_time: Long? = null
)

@Serializable
data class FeeEstimates(
    val fastestFee: Int,
    val halfHourFee: Int,
    val hourFee: Int,
    val economyFee: Int,
    val minimumFee: Int
)

// ============ Implementation ============

class MempoolClientImpl(
    private val baseUrl: String = "https://mempool.space/api",
    private val client: HttpClient
) : MempoolClient {

    // mempool.space public API rate-limits aggressive parallel requests.
    // Semaphore limits concurrent in-flight requests to avoid 429s.
    private val rateLimiter = Semaphore(5)

    override suspend fun getAddressInfo(address: String): AddressInfo {
        return rateLimiter.withPermit {
            client.get("$baseUrl/address/$address").body()
        }
    }

    override suspend fun getAddressUtxos(address: String): List<MempoolUtxo> {
        return rateLimiter.withPermit {
            client.get("$baseUrl/address/$address/utxo").body()
        }
    }

    override suspend fun getAddressTransactions(address: String): List<MempoolTransaction> {
        return rateLimiter.withPermit {
            client.get("$baseUrl/address/$address/txs").body()
        }
    }

    override suspend fun getFeeEstimates(): FeeEstimates {
        return client.get("$baseUrl/v1/fees/recommended").body()
    }

    override suspend fun getTransaction(txid: String): MempoolTransaction {
        return client.get("$baseUrl/tx/$txid").body()
    }

    override suspend fun broadcastTransaction(hex: String): String {
        val response: HttpResponse = client.post("$baseUrl/tx") {
            contentType(ContentType.Text.Plain)
            setBody(hex)
        }
        if (!response.status.isSuccess()) {
            throw MempoolBroadcastException(response.status.value, response.bodyAsText())
        }
        return response.bodyAsText() // Returns txid
    }

    override suspend fun hasActivity(address: String): Boolean {
        return try {
            val info = getAddressInfo(address)
            info.txCount > 0
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getTipHeight(): Int {
        // Mempool.space vrací číslo jako plain text, ne JSON
        val response: HttpResponse = client.get("$baseUrl/blocks/tip/height")
        return response.bodyAsText().trim().toInt()
    }
}

class MempoolBroadcastException(val statusCode: Int, val body: String) : 
    Exception("Broadcast failed ($statusCode): $body")
