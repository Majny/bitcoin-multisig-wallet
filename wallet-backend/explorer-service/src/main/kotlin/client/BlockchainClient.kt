package cz.majny.wallet.explorer.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("BlockchainClient")

/**
 * HTTP client pro blockchain-service.
 * Získává on-chain data: UTXOs, transakce, fee odhady.
 */
class BlockchainClient(
    private val baseUrl: String,
    private val client: HttpClient
) {

    /**
     * Vrátí info o adrese (zůstatek, počet tx).
     */
    suspend fun getAddressInfo(address: String): AddressInfo {
        return client.get("$baseUrl/api/v1/blockchain/address/$address").body()
    }

    /**
     * Vrátí UTXOs pro danou adresu.
     */
    suspend fun getAddressUtxos(address: String): List<UtxoInfo> {
        val response: HttpResponse = client.get("$baseUrl/api/v1/blockchain/address/$address/utxos")
        if (!response.status.isSuccess()) {
            throw Exception("blockchain-service error ${response.status.value} for $address/utxos")
        }
        return response.body()
    }

    /**
     * Vrátí transakce pro danou adresu.
     */
    suspend fun getAddressTransactions(address: String): List<RawTransaction> {
        val response: HttpResponse = client.get("$baseUrl/api/v1/blockchain/address/$address/txs")
        if (!response.status.isSuccess()) {
            throw Exception("blockchain-service error ${response.status.value} for $address/txs")
        }
        return response.body()
    }

    /**
     * Zjistí, zda adresa má aktivitu (pro gap limit).
     */
    suspend fun hasActivity(address: String): Boolean {
        val resp: HasActivityResponse = client.get("$baseUrl/api/v1/blockchain/address/$address/has-activity").body()
        return resp.hasActivity
    }

    /**
     * Vrátí detail jedné transakce.
     */
    suspend fun getTransaction(txid: String): RawTransaction {
        return client.get("$baseUrl/api/v1/blockchain/tx/$txid").body()
    }

    /**
     * Vrátí doporučené fee rates.
     */
    suspend fun getFeeEstimates(): FeeEstimates {
        return client.get("$baseUrl/api/v1/blockchain/fees").body()
    }

    /**
     * Vrátí výšku aktuálního nejlepšího bloku.
     * Používá se pro výpočet reálného počtu konfirmací.
     */
    suspend fun getTipHeight(): Int {
        val resp: TipHeightResponse = client.get("$baseUrl/api/v1/blockchain/tip/height").body()
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
    val balance: Long
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
