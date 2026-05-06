package cz.majny.wallet.blockchain.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.network.sockets.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.IOException

/*
 * Client for Esplora-compatible blockchain APIs - used with Blockstream on
 * mainnet and Mempool.space on testnet4. Both expose the same endpoint shape
 * so a single interface covers both.
 *
 *   Mainnet:  https://blockstream.info/api/
 *   Testnet4: https://mempool.space/testnet4/api/
 */
interface MempoolClient {

    /* Address info - tx_count + funded/spent sums. Used both for dashboard
     * balance and BIP-44 account discovery. */
    suspend fun getAddressInfo(address: String): AddressInfo

    /* UTXOs for a single address, used by coin control. */
    suspend fun getAddressUtxos(address: String): List<MempoolUtxo>

    /* Raw tx history for a single address. */
    suspend fun getAddressTransactions(address: String): List<MempoolTransaction>

    /* Current sat/vB recommendations (derived from fee-estimates). */
    suspend fun getFeeEstimates(): FeeEstimates

    /* Single transaction detail. */
    suspend fun getTransaction(txid: String): MempoolTransaction

    /* Push a signed raw tx. Returns the txid on success, throws
     * MempoolBroadcastException with the upstream body on rejection. */
    suspend fun broadcastTransaction(hex: String): String

    /* Activity probe used during gap-limit scanning - just the boolean. */
    suspend fun hasActivity(address: String): Boolean

    /* Current chain tip height. Needed to convert a tx's block_height into
     * a confirmations count. */
    suspend fun getTipHeight(): Int

    /* Raw hex of the full transaction (witness serialization). Needed for
     * PSBT_IN_NON_WITNESS_UTXO - Trezor firmware 2.3.1+ requires the full
     * previous tx for every input, even native segwit. */
    suspend fun getRawTransaction(txid: String): String
}

// DTOs

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

// Implementation

class MempoolClientImpl(
    private val baseUrl: String = "https://mempool.space/api",
    // Separate base URL for the fee oracle. Blockstream's /fee-estimates
    // collapses to ~1 sat/vB across every target during low congestion, so
    // we route fee queries to Mempool.space's /v1/fees/recommended which
    // keeps the priority levels distinguishable. Defaults to baseUrl when
    // not provided so test code that doesn't care can stay terse.
    private val feesUrl: String = baseUrl,
    private val client: HttpClient
) : MempoolClient {

    private val log = LoggerFactory.getLogger(MempoolClientImpl::class.java)

    // Fee estimate cache. Mempool.space's /v1/fees/recommended is rate-limited
    // (~60 RPM anonymous) and fees realistically change on the order of one
    // block (~10 min), so a 30 s in-memory cache covers every Send screen
    // visit without hitting the upstream more than twice a minute even
    // under heavy concurrent use. Mutex serialises the recompute so a
    // simultaneous miss doesn't fan out into N requests.
    @Volatile private var cachedFees: FeeEstimates? = null
    @Volatile private var cachedFeesAt: Long = 0L
    private val feesCacheMutex = Mutex()
    private val feesTtlMillis: Long = 30_000L

    // 5 concurrent requests keeps Blockstream happy (no rate-limiting).
    // Higher concurrency causes 429s and hung connections that add 30+ s.
    private val rateLimiter = Semaphore(5)

    /*
     * GET with a single retry on transient failures (429, request timeout,
     * connect/socket timeout, generic IO error). The 1 s delay is deliberately
     * outside withPermit so other queued requests can proceed during the wait.
     *
     * Only one retry - if the IP is in a penalty box, retrying further just
     * keeps the queue backed up for 20+ s; better to fail fast.
     *
     * Catching IOException on the first attempt is load-bearing for account
     * discovery: a swallowed connection reset would be treated as "no
     * activity" and the BIP-44 gap-limit scan would stop prematurely.
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
            // Connection reset, broken pipe, etc. - transport-level failures
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
        // Cached?
        val now = System.currentTimeMillis()
        val cached = cachedFees
        if (cached != null && now - cachedFeesAt < feesTtlMillis) {
            return cached
        }

        // Single-flight: serialise concurrent misses through a mutex so a
        // burst of users hitting Send simultaneously only causes ONE upstream
        // request, not N. The first holder fills the cache; everyone else
        // either hits the cache on re-check or gets the fresh result.
        return feesCacheMutex.withLock {
            val recheck = cachedFees
            if (recheck != null && System.currentTimeMillis() - cachedFeesAt < feesTtlMillis) {
                return@withLock recheck
            }
            val fresh = fetchFeesFromUpstream()
            cachedFees = fresh
            cachedFeesAt = System.currentTimeMillis()
            fresh
        }
    }

    /*
     * Bypasses the cache and goes straight to the upstream fee oracle. Try
     * Mempool.space's /v1/fees/recommended first (priority levels stay
     * distinct even when the mempool is empty), then fall back to Esplora's
     * /fee-estimates if that is rate-limited or unavailable.
     */
    private suspend fun fetchFeesFromUpstream(): FeeEstimates {
        // Primary: Mempool.space's /v1/fees/recommended. The response shape
        // is exactly our FeeEstimates DTO so we deserialize it directly.
        // Even when the mempool is empty this endpoint keeps the priority
        // levels distinct (e.g. fastestFee=3, halfHourFee=1, hourFee=1)
        // because it has its own block-time prediction model on top of the
        // raw projections. The Esplora /fee-estimates endpoint, by contrast,
        // collapses everything to ~1 sat/vB during low congestion which
        // makes the Low/Medium/High selector meaningless.
        try {
            return getChecked("$feesUrl/v1/fees/recommended").body<FeeEstimates>()
        } catch (e: Exception) {
            log.warn("Mempool.space /v1/fees/recommended unavailable, falling back to /fee-estimates: {}", e.message)
        }

        // Fallback: Esplora /fee-estimates returns
        // {"1": 10.0, "3": 7.0, "6": 5.0, ...} where the key is the
        // confirmation target in blocks. Use the same baseUrl as the rest
        // of the API since this is the chain's own oracle.
        val fees: Map<String, Double> = getChecked("$baseUrl/fee-estimates").body()
        fun pick(target: Int): Int {
            val exact = fees[target.toString()]
            if (exact != null) return exact.toInt().coerceAtLeast(1)
            // Fall back to the nearest available target <= requested so we
            // never over-promise a faster confirmation than the API implies.
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
        // Broadcast is intentionally not routed through getChecked - a retry
        // on a timeout could double-submit the same tx.
        val response: HttpResponse = client.post("$baseUrl/tx") {
            contentType(ContentType.Text.Plain)
            setBody(hex)
        }
        if (!response.status.isSuccess()) {
            throw MempoolBroadcastException(response.status.value, response.bodyAsText())
        }
        return response.bodyAsText()
    }

    override suspend fun hasActivity(address: String): Boolean {
        // Don't swallow errors - the caller needs to distinguish "no activity"
        // from "blockchain unreachable", otherwise account discovery would
        // treat a transient outage as an empty account.
        val info = getAddressInfo(address)
        return info.txCount > 0
    }

    override suspend fun getTipHeight(): Int {
        // Esplora returns the height as plain text, not JSON.
        val response: HttpResponse = getChecked("$baseUrl/blocks/tip/height")
        val text = response.bodyAsText().trim()
        return try {
            text.toInt()
        } catch (e: NumberFormatException) {
            throw RuntimeException("Invalid tip height response: '$text'")
        }
    }

    override suspend fun getRawTransaction(txid: String): String {
        // Esplora returns the raw tx hex as plain text.
        val response: HttpResponse = getChecked("$baseUrl/tx/$txid/hex")
        return response.bodyAsText().trim()
    }
}

class MempoolBroadcastException(val statusCode: Int, val body: String) : 
    Exception("Broadcast failed ($statusCode): $body")
