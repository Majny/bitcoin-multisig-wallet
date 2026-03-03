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

/**
 * HTTP client pro blockchain-service.
 * Získává on-chain data: UTXOs, transakce, fee odhady.
 */
class BlockchainClient(
    private val baseUrl: String,
    private val client: HttpClient
) {
    private val addressInfoCache = ConcurrentHashMap<String, Pair<Long, AddressInfo>>()

    // Deduplication map: balance + transactions fire 30 getAddressInfo calls each
    // at the same instant. ConcurrentHashMap.putIfAbsent ensures only ONE network
    // request is made per address; the other coroutine awaits the same Deferred.
    private val pendingInfoRequests = ConcurrentHashMap<String, CompletableDeferred<AddressInfo>>()

    /**
     * Vrátí info o adrese. Výsledek je cachován na 60 s.
     * Souběžné requesty na stejnou adresu jsou deduplikovány — druhý čeká
     * na výsledek prvního místo toho, aby spustil vlastní HTTP request.
     */
    suspend fun getAddressInfo(address: String, network: String = "mainnet"): AddressInfo {
        val cacheKey = "$network:$address"
        val now = System.currentTimeMillis()
        val cached = addressInfoCache[cacheKey]
        if (cached != null && now - cached.first < 60_000L) return cached.second

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

    /**
     * Vrátí UTXOs pro danou adresu.
     */
    suspend fun getAddressUtxos(address: String, network: String = "mainnet"): List<UtxoInfo> {
        val response: HttpResponse = client.get("$baseUrl/api/v1/blockchain/address/$address/utxos") {
            parameter("network", network)
        }
        if (!response.status.isSuccess()) {
            throw Exception("blockchain-service error ${response.status.value} for $address/utxos")
        }
        return response.body()
    }

    /**
     * Vrátí transakce pro danou adresu.
     */
    suspend fun getAddressTransactions(address: String, network: String = "mainnet"): List<RawTransaction> {
        val response: HttpResponse = client.get("$baseUrl/api/v1/blockchain/address/$address/txs") {
            parameter("network", network)
        }
        if (!response.status.isSuccess()) {
            throw Exception("blockchain-service error ${response.status.value} for $address/txs")
        }
        return response.body()
    }

    /**
     * Zjistí, zda adresa má aktivitu (pro gap limit).
     */
    suspend fun hasActivity(address: String, network: String = "mainnet"): Boolean {
        val resp: HasActivityResponse = client.get("$baseUrl/api/v1/blockchain/address/$address/has-activity") {
            parameter("network", network)
        }.body()
        return resp.hasActivity
    }

    /**
     * Vrátí detail jedné transakce.
     */
    suspend fun getTransaction(txid: String, network: String = "mainnet"): RawTransaction {
        val response: HttpResponse = client.get("$baseUrl/api/v1/blockchain/tx/$txid") {
            parameter("network", network)
        }
        if (!response.status.isSuccess()) {
            throw Exception("blockchain-service error ${response.status.value} for tx/$txid")
        }
        return response.body()
    }

    /**
     * Vrátí doporučené fee rates.
     */
    suspend fun getFeeEstimates(network: String = "mainnet"): FeeEstimates {
        return client.get("$baseUrl/api/v1/blockchain/fees") {
            parameter("network", network)
        }.body()
    }

    /**
     * Vrátí výšku aktuálního nejlepšího bloku.
     * Používá se pro výpočet reálného počtu konfirmací.
     */
    suspend fun getTipHeight(network: String = "mainnet"): Int {
        val resp: TipHeightResponse = client.get("$baseUrl/api/v1/blockchain/tip/height") {
            parameter("network", network)
        }.body()
        return resp.height
    }
}

@kotlinx.serialization.Serializable
data class TipHeightResponse(val height: Int)

// ============ DTOs (mirror blockchain-service / Mempool.space responses) ============

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
