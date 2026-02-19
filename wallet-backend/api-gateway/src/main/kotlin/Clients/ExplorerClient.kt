package cz.majny.wallet.gateway.clients

import cz.majny.wallet.gateway.config.AppConfig
import cz.majny.wallet.gateway.dto.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*

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

    override suspend fun getUtxos(walletId: String): List<UtxoDto> {
        requireAttached(this::client.isInitialized, "explorer")
        val resp = upstreamRequest("explorer") {
            client.get("${cfg.explorerBaseUrl}/explorer/wallet/$walletId/utxos")
        }.ensureSuccess("explorer")
        val body: WalletUtxosDto = resp.body()
        return body.utxos.map { UtxoDto(it.txid, it.vout, it.valueSats, it.address, null) }
    }

    override suspend fun getWalletBalance(walletId: String): WalletBalanceDto {
        requireAttached(this::client.isInitialized, "explorer")
        val resp = upstreamRequest("explorer") {
            client.get("${cfg.explorerBaseUrl}/explorer/wallet/$walletId/balance")
        }.ensureSuccess("explorer")
        return resp.body()
    }

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

    override suspend fun getWalletUtxos(walletId: String): WalletUtxosDto {
        requireAttached(this::client.isInitialized, "explorer")
        val resp = upstreamRequest("explorer") {
            client.get("${cfg.explorerBaseUrl}/explorer/wallet/$walletId/utxos")
        }.ensureSuccess("explorer")
        return resp.body()
    }

    override suspend fun getReceiveAddress(walletId: String): ReceiveAddressDto {
        requireAttached(this::client.isInitialized, "explorer")
        val resp = upstreamRequest("explorer") {
            client.get("${cfg.explorerBaseUrl}/explorer/wallet/$walletId/receive-address")
        }.ensureSuccess("explorer")
        return resp.body()
    }

    override suspend fun getTransactionDetail(txid: String, walletId: String?): TransactionDetailDto {
        requireAttached(this::client.isInitialized, "explorer")
        val resp = upstreamRequest("explorer") {
            client.get("${cfg.explorerBaseUrl}/explorer/tx/$txid/detail") {
                walletId?.let { parameter("walletId", it) }
            }
        }.ensureSuccess("explorer")
        return resp.body()
    }

    override suspend fun getFeeEstimates(): FeeEstimatesExplorerDto {
        requireAttached(this::client.isInitialized, "explorer")
        val resp = upstreamRequest("explorer") {
            client.get("${cfg.explorerBaseUrl}/explorer/fees")
        }.ensureSuccess("explorer")
        return resp.body()
    }
}
