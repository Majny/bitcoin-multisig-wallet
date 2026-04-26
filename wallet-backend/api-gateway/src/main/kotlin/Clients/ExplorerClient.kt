package cz.majny.wallet.gateway.clients

import cz.majny.wallet.gateway.config.AppConfig
import cz.majny.wallet.gateway.dto.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*

/* HTTP client facade for explorer-service endpoints used by gateway routes. */
interface ExplorerClient {
    suspend fun getUtxos(walletId: String): List<UtxoDto>
    suspend fun getWalletBalance(walletId: String): WalletBalanceDto
    suspend fun getWalletTransactions(walletId: String, limit: Int?, offset: Int?): WalletTransactionsDto
    suspend fun getWalletUtxos(walletId: String): WalletUtxosDto
    suspend fun getReceiveAddress(walletId: String): ReceiveAddressDto
    suspend fun getTransactionDetail(txid: String, walletId: String?): TransactionDetailDto
    suspend fun getFeeEstimates(): FeeEstimatesExplorerDto
}

class ExplorerClientImpl(private val cfg: AppConfig) : ExplorerClient {
    private lateinit var client: HttpClient
    fun attach(http: HttpClient) { client = http }

    /* GET /explorer/wallet/{walletId}/utxos — flat UTXO list, downscaled DTO
     * (no scriptPubKey). Used by routes that don't need the full enriched view. */
    override suspend fun getUtxos(walletId: String): List<UtxoDto> {
        requireAttached(this::client.isInitialized, "explorer")
        val resp = upstreamRequest("explorer") {
            client.get("${cfg.explorerBaseUrl}/explorer/wallet/$walletId/utxos")
        }.ensureSuccess("explorer")
        val body: WalletUtxosDto = resp.body()
        return body.utxos.map { UtxoDto(it.txid, it.vout, it.valueSats, it.address, null) }
    }

    /* GET /explorer/wallet/{walletId}/balance — confirmed + unconfirmed totals. */
    override suspend fun getWalletBalance(walletId: String): WalletBalanceDto {
        requireAttached(this::client.isInitialized, "explorer")
        val resp = upstreamRequest("explorer") {
            client.get("${cfg.explorerBaseUrl}/explorer/wallet/$walletId/balance")
        }.ensureSuccess("explorer")
        return resp.body()
    }

    /* GET /explorer/wallet/{walletId}/transactions — paginated tx history with
     * SENT/RECEIVED classification done on the explorer side. */
    override suspend fun getWalletTransactions(walletId: String, limit: Int?, offset: Int?): WalletTransactionsDto {
        requireAttached(this::client.isInitialized, "explorer")
        val resp = upstreamRequest("explorer") {
            client.get("${cfg.explorerBaseUrl}/explorer/wallet/$walletId/transactions") {
                limit?.let { parameter("limit", it) }
                offset?.let { parameter("offset", it) }
            }
        }.ensureSuccess("explorer")
        return resp.body()
    }

    /* GET /explorer/wallet/{walletId}/utxos — full UTXO list enriched with address
     * derivation info, used by the coin-control screen. */
    override suspend fun getWalletUtxos(walletId: String): WalletUtxosDto {
        requireAttached(this::client.isInitialized, "explorer")
        val resp = upstreamRequest("explorer") {
            client.get("${cfg.explorerBaseUrl}/explorer/wallet/$walletId/utxos")
        }.ensureSuccess("explorer")
        return resp.body()
    }

    /* GET /explorer/wallet/{walletId}/receive-address — first unused receive
     * address. Discovers fresh ones if every pre-derived address is used. */
    override suspend fun getReceiveAddress(walletId: String): ReceiveAddressDto {
        requireAttached(this::client.isInitialized, "explorer")
        val resp = upstreamRequest("explorer") {
            client.get("${cfg.explorerBaseUrl}/explorer/wallet/$walletId/receive-address")
        }.ensureSuccess("explorer")
        return resp.body()
    }

    /* GET /explorer/tx/{txid}/detail — inputs/outputs annotated with which
     * addresses belong to the supplied walletId (for the YOURS badge). */
    override suspend fun getTransactionDetail(txid: String, walletId: String?): TransactionDetailDto {
        requireAttached(this::client.isInitialized, "explorer")
        val resp = upstreamRequest("explorer") {
            client.get("${cfg.explorerBaseUrl}/explorer/tx/$txid/detail") {
                walletId?.let { parameter("walletId", it) }
            }
        }.ensureSuccess("explorer")
        return resp.body()
    }

    /* GET /explorer/fees — current sat/vB recommendations (low/medium/high). */
    override suspend fun getFeeEstimates(): FeeEstimatesExplorerDto {
        requireAttached(this::client.isInitialized, "explorer")
        val resp = upstreamRequest("explorer") {
            client.get("${cfg.explorerBaseUrl}/explorer/fees")
        }.ensureSuccess("explorer")
        return resp.body()
    }
}
