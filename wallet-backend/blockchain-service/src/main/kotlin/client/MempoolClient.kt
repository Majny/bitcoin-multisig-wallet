package cz.majny.wallet.blockchain.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.network.sockets.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * Client for Mempool.space public API.
 * 
 * Mainnet: https://mempool.space/api/
 * Testnet4: https://mempool.space/testnet4/api/
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

    /**
     * Vrátí raw hex celé transakce (witness serialization).
     * Potřebné pro PSBT_IN_NON_WITNESS_UTXO — Trezor firmware 2.4+ vyžaduje
     * celou předchozí transakci pro všechny vstupy (i native segwit).
     */
    suspend fun getRawTransaction(txid: String): String
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

    private val log = LoggerFactory.getLogger(MempoolClientImpl::class.java)

    // 5 concurrent requests keeps Blockstream happy (no rate-limiting).
    // Higher concurrency causes 429s and hung connections that add 30+ s.
    private val rateLimiter = Semaphore(5)

    /**
     * Executes a GET request with one retry on transient failures:
     *   - 429 Too Many Requests
     *   - HTTP request timeout
     *   - Connection reset / socket timeout / generic IO error
     *
     * The delay is outside withPermit so other queued requests can proceed during the wait.
     * Only 1 retry: if the IP is in a penalty box, retrying many times just
     * keeps the queue backed up for 20+ seconds — better to fail fast.
     *
     * Note: catching IOException on the first attempt is critical for account
     * discovery — otherwise a single connection reset would be silently treated
     * as "no activity" and the BIP-44 gap limit would stop discovery prematurely.
     */
    private suspend fun getChecked(url: String): HttpResponse {
        val first = try {
            rateLimiter.withPermit { client.get(url) }
        } catch (e: HttpRequestTimeoutException) {
            log.warn("Mempool request timeout on first attempt, will retry: {}", url)
            null
        } catch (e: ConnectTimeoutException) {
            log.warn("Mempool connect timeout on first attempt, will retry: {}", url)
            null
        } catch (e: SocketTimeoutException) {
            log.warn("Mempool socket timeout on first attempt, will retry: {}", url)
            null
        } catch (e: IOException) {
            // Connection reset, broken pipe, etc. — transport-level failures
            log.warn("Mempool IO error on first attempt ({}), will retry: {}", e.message, url)
            null
        }
        if (first != null && first.status != HttpStatusCode.TooManyRequests) {
            if (!first.status.isSuccess()) {
                throw RuntimeException("Mempool API error ${first.status.value} for: $url")
            }
            return first
        }
        delay(1000L)
        val retry = try {
            rateLimiter.withPermit { client.get(url) }
        } catch (e: IOException) {
            throw RuntimeException("Mempool API unreachable after retry for: $url", e)
        } catch (e: HttpRequestTimeoutException) {
            throw RuntimeException("Mempool API timeout after retry for: $url", e)
        }
        if (retry.status == HttpStatusCode.TooManyRequests) {
            throw RuntimeException("Mempool API rate limit exceeded after retry for: $url")
        }
        if (!retry.status.isSuccess()) {
            throw RuntimeException("Mempool API error ${retry.status.value} after retry for: $url")
        }
        return retry
    }

    override suspend fun getAddressInfo(address: String): AddressInfo =
        getChecked("$baseUrl/address/$address").body()

    override suspend fun getAddressUtxos(address: String): List<MempoolUtxo> =
        getChecked("$baseUrl/address/$address/utxo").body()

    override suspend fun getAddressTransactions(address: String): List<MempoolTransaction> =
        getChecked("$baseUrl/address/$address/txs").body()

    override suspend fun getFeeEstimates(): FeeEstimates {
        // Blockstream Esplora: GET /fee-estimates
        // Returns {"1": 10.0, "3": 7.0, "6": 5.0, ...} — key = confirmation target in blocks
        val fees: Map<String, Double> = getChecked("$baseUrl/fee-estimates").body()
        fun pick(target: Int): Int {
            val exact = fees[target.toString()]
            if (exact != null) return exact.toInt().coerceAtLeast(1)
            // nearest available target that is <= requested
            return fees.entries
                .mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }
                .filter { (k, _) -> k <= target }
                .maxByOrNull { (k, _) -> k }
                ?.second?.toInt()?.coerceAtLeast(1) ?: 1
        }
        return FeeEstimates(
            fastestFee  = pick(1),
            halfHourFee = pick(3),
            hourFee     = pick(6),
            economyFee  = pick(24),
            minimumFee  = pick(144)
        )
    }

    override suspend fun getTransaction(txid: String): MempoolTransaction {
        return getChecked("$baseUrl/tx/$txid").body()
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
        // Don't swallow errors — caller needs to know if blockchain is unreachable
        // to avoid false-negative account discovery
        val info = getAddressInfo(address)
        return info.txCount > 0
    }

    override suspend fun getTipHeight(): Int {
        // Mempool.space vrací číslo jako plain text, ne JSON
        val response: HttpResponse = getChecked("$baseUrl/blocks/tip/height")
        val text = response.bodyAsText().trim()
        return try {
            text.toInt()
        } catch (e: NumberFormatException) {
            throw RuntimeException("Invalid tip height response: '$text'")
        }
    }

    override suspend fun getRawTransaction(txid: String): String {
        // Mempool.space vrací raw hex transakce jako plain text
        val response: HttpResponse = getChecked("$baseUrl/tx/$txid/hex")
        return response.bodyAsText().trim()
    }
}

class MempoolBroadcastException(val statusCode: Int, val body: String) : 
    Exception("Broadcast failed ($statusCode): $body")
