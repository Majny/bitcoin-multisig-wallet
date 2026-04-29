package cz.majny.wallet.explorer.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("BlockchainClient")

/*
 * HTTP client for blockchain-service. Pulls on-chain data (UTXOs,
 * transactions, fee estimates) and caches AddressInfo to collapse the
 * balance-then-tx-history double fetch that the dashboard triggers.
 */
class BlockchainClient(
    private val baseUrl: String,
    private val client: HttpClient
) {
    private val addressInfoCache = ConcurrentHashMap<String, Pair<Long, AddressInfo>>()

    // In-flight deduplication: balance + transactions fan out ~30
    // getAddressInfo calls each at the same instant. putIfAbsent makes sure
    // only one network request actually flies; the rest await the same
    // CompletableDeferred.
    private val pendingInfoRequests = ConcurrentHashMap<String, CompletableDeferred<AddressInfo>>()

    private companion object {
        const val CACHE_TTL = 60_000L
    }

    /*
     * Address info (tx count, balance, UTXO count). Cached for 60 s,
     * concurrent duplicate requests are coalesced into one HTTP call.
     */
    suspend fun getAddressInfo(address: String, network: String = "mainnet"): AddressInfo {
        val cacheKey = "$network:$address"
        val now = System.currentTimeMillis()

        // Lazy eviction - good enough for a dev-scale service. The 10k
        // threshold is a rough guardrail against unbounded memory growth.
        if (addressInfoCache.size > 10_000) {
            addressInfoCache.entries.removeIf { now - it.value.first > CACHE_TTL }
        }

        val cached = addressInfoCache[cacheKey]
        if (cached != null && now - cached.first < CACHE_TTL) return cached.second

        val newDeferred = CompletableDeferred<AddressInfo>()
        val existing = pendingInfoRequests.putIfAbsent(cacheKey, newDeferred)
        if (existing != null) return existing.await()

        return try {
            val response: HttpResponse = client.get("$baseUrl/api/v1/blockchain/address/$address") {
                parameter("network", network)
            }
            if (!response.status.isSuccess()) {
                throw Exception("blockchain-service error ${response.status.value} for $address")
            }
            val info: AddressInfo = response.body()
            addressInfoCache[cacheKey] = System.currentTimeMillis() to info
            newDeferred.complete(info)
            info
        } catch (e: Exception) {
            newDeferred.completeExceptionally(e)
            throw e
        } finally {
            pendingInfoRequests.remove(cacheKey)
        }
    }

    /* UTXO list for a single address. */
    suspend fun getAddressUtxos(address: String, network: String = "mainnet"): List<UtxoInfo> {
        val response: HttpResponse = client.get("$baseUrl/api/v1/blockchain/address/$address/utxos") {
            parameter("network", network)
        }
        if (!response.status.isSuccess()) {
            throw Exception("blockchain-service error ${response.status.value} for $address/utxos")
        }
        return response.body()
    }

    /* Raw tx history for a single address. */
    suspend fun getAddressTransactions(address: String, network: String = "mainnet"): List<RawTransaction> {
        val response: HttpResponse = client.get("$baseUrl/api/v1/blockchain/address/$address/txs") {
            parameter("network", network)
        }
        if (!response.status.isSuccess()) {
            throw Exception("blockchain-service error ${response.status.value} for $address/txs")
        }
        return response.body()
    }

    /* Lightweight activity probe, used during gap-limit scanning. */
    suspend fun hasActivity(address: String, network: String = "mainnet"): Boolean {
        val response: HttpResponse = client.get("$baseUrl/api/v1/blockchain/address/$address/has-activity") {
            parameter("network", network)
        }
        if (!response.status.isSuccess()) {
            throw Exception("blockchain-service error ${response.status.value} for $address/has-activity")
        }
        val resp: HasActivityResponse = response.body()
        return resp.hasActivity
    }

    /* Single transaction detail. */
    suspend fun getTransaction(txid: String, network: String = "mainnet"): RawTransaction {
        val response: HttpResponse = client.get("$baseUrl/api/v1/blockchain/tx/$txid") {
            parameter("network", network)
        }
        if (!response.status.isSuccess()) {
            throw Exception("blockchain-service error ${response.status.value} for tx/$txid")
        }
        return response.body()
    }

    /* Current sat/vB recommendations (low/medium/high). */
    suspend fun getFeeEstimates(network: String = "mainnet"): FeeEstimates {
        val response: HttpResponse = client.get("$baseUrl/api/v1/blockchain/fees") {
            parameter("network", network)
        }
        if (!response.status.isSuccess()) {
            throw Exception("blockchain-service error ${response.status.value} for fees")
        }
        return response.body()
    }

    /*
     * Current chain tip height. Needed to convert a tx's block_height into
     * a real confirmations count.
     */
    suspend fun getTipHeight(network: String = "mainnet"): Int {
        val response: HttpResponse = client.get("$baseUrl/api/v1/blockchain/tip/height") {
            parameter("network", network)
        }
        if (!response.status.isSuccess()) {
            throw Exception("blockchain-service error ${response.status.value} for tip/height")
        }
        val resp: TipHeightResponse = response.body()
        return resp.height
    }
}

@kotlinx.serialization.Serializable
data class TipHeightResponse(val height: Int)

// DTOs (mirror blockchain-service / Mempool.space responses)

@Serializable
data class AddressInfo(
    val address: String,
    val txCount: Int,
    val balance: Long,
    val confirmedBalance: Long = 0,
    val unconfirmedBalance: Long = 0,
    val utxoCount: Int = 0
)

@Serializable
data class HasActivityResponse(
    val address: String,
    val hasActivity: Boolean
)

@Serializable
data class UtxoInfo(
    val txid: String,
    val vout: Int,
    val value: Long,
    val status: TxConfirmationStatus = TxConfirmationStatus()
)

@Serializable
data class TxConfirmationStatus(
    val confirmed: Boolean = false,
    val block_height: Int? = null,
    val block_hash: String? = null,
    val block_time: Long? = null
)

@Serializable
data class RawTransaction(
    val txid: String,
    val version: Int = 2,
    val locktime: Int = 0,
    val size: Int = 0,
    val weight: Int = 0,
    val fee: Long = 0,
    val vin: List<TxInput> = emptyList(),
    val vout: List<TxOutput> = emptyList(),
    val status: TxConfirmationStatus = TxConfirmationStatus()
)

@Serializable
data class TxInput(
    val txid: String = "",
    val vout: Int = 0,
    val prevout: TxPrevout? = null,
    val scriptsig: String = "",
    val scriptsig_asm: String = "",
    val witness: List<String> = emptyList(),
    val is_coinbase: Boolean = false,
    val sequence: Long = 0
)

@Serializable
data class TxPrevout(
    val scriptpubkey: String = "",
    val scriptpubkey_asm: String = "",
    val scriptpubkey_type: String = "",
    val scriptpubkey_address: String = "",
    val value: Long = 0
)

@Serializable
data class TxOutput(
    val scriptpubkey: String = "",
    val scriptpubkey_asm: String = "",
    val scriptpubkey_type: String = "",
    val scriptpubkey_address: String = "",
    val value: Long = 0
)

@Serializable
data class FeeEstimates(
    val fastestFee: Int,
    val halfHourFee: Int,
    val hourFee: Int,
    val economyFee: Int,
    val minimumFee: Int
)
