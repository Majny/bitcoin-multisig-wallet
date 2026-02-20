package com.example.bitcoinwallet.core.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * API client for wallet data.
 * Communicates with API Gateway which routes to explorer-service, blockchain-service, and price-service.
 */
class WalletApiClient(
    private val baseUrl: String,
    private val client: HttpClient = defaultClient()
) {
    
    // ============ Explorer Endpoints (wallet-level) ============
    
    /**
     * GET /api/v1/explorer/wallet/{walletId}/balance
     * Aggregated balance across all wallet addresses.
     */
    suspend fun getWalletBalance(walletId: String, accessToken: String): WalletBalanceDto {
        return client.get("$baseUrl/explorer/wallet/$walletId/balance") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }
    
    /**
     * GET /api/v1/explorer/wallet/{walletId}/transactions?limit=50&offset=0
     * Transaction history with SENT/RECEIVED classification.
     */
    suspend fun getWalletTransactions(
        walletId: String,
        accessToken: String,
        limit: Int = 50,
        offset: Int = 0
    ): WalletTransactionsDto {
        return client.get("$baseUrl/explorer/wallet/$walletId/transactions") {
            header("Authorization", "Bearer $accessToken")
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()
    }
    
    /**
     * GET /api/v1/explorer/wallet/{walletId}/utxos
     * All UTXOs enriched with address info for coin control.
     */
    suspend fun getWalletUtxos(walletId: String, accessToken: String): WalletUtxosDto {
        return client.get("$baseUrl/explorer/wallet/$walletId/utxos") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }
    
    /**
     * GET /api/v1/explorer/wallet/{walletId}/receive-address
     * First unused receive address.
     */
    suspend fun getReceiveAddress(walletId: String, accessToken: String): ReceiveAddressDto {
        return client.get("$baseUrl/explorer/wallet/$walletId/receive-address") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }
    
    /**
     * GET /api/v1/explorer/tx/{txid}
     * Transaction detail with inputs/outputs.
     */
    suspend fun getTransactionDetail(txid: String, accessToken: String, walletId: String? = null): TransactionDetailDto {
        return client.get("$baseUrl/explorer/tx/$txid/detail") {
            header("Authorization", "Bearer $accessToken")
            walletId?.let { parameter("walletId", it) }
        }.body()
    }
    
    /**
     * GET /api/v1/explorer/fees
     * Recommended fee rates.
     */
    suspend fun getFeeEstimates(accessToken: String): FeeEstimatesDto {
        return client.get("$baseUrl/explorer/fees") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }
    
    // ============ Price Endpoints ============
    
    /**
     * GET /api/v1/price
     * Get current Bitcoin prices in various currencies.
     */
    suspend fun getBitcoinPrices(accessToken: String, currencies: String = "czk,usd,eur"): BitcoinPricesDto {
        return client.get("$baseUrl/price") {
            header("Authorization", "Bearer $accessToken")
            parameter("currencies", currencies)
        }.body()
    }
    
    /**
     * GET /api/v1/price/convert
     * Convert satoshis to fiat value.
     */
    suspend fun convertSatsToFiat(accessToken: String, sats: Long, currency: String = "czk"): ConversionResultDto {
        return client.get("$baseUrl/price/convert") {
            header("Authorization", "Bearer $accessToken")
            parameter("sats", sats)
            parameter("currency", currency)
        }.body()
    }
    
    companion object {
        private fun defaultClient(): HttpClient =
            HttpClient(Android) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            prettyPrint = false
                            isLenient = true
                            explicitNulls = false
                        }
                    )
                }
            }
    }
}

// ============ Explorer DTOs ============

@Serializable
data class WalletBalanceDto(
    val walletId: String,
    val confirmedSats: Long,
    val unconfirmedSats: Long,
    val totalSats: Long,
    val utxoCount: Int,
    val addressCount: Int
)

@Serializable
data class WalletTransactionsDto(
    val walletId: String,
    val transactions: List<WalletTransactionDto>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

@Serializable
data class WalletTransactionDto(
    val txid: String,
    val type: String,           // "SENT" or "RECEIVED"
    val amountSats: Long,
    val fee: Long = 0,
    val confirmed: Boolean,
    val blockHeight: Int? = null,
    val blockTime: Long? = null,
    val confirmations: Int = 0,
    val inputCount: Int = 0,
    val outputCount: Int = 0,
    val size: Int = 0,
    val weight: Int = 0
)

@Serializable
data class WalletUtxosDto(
    val walletId: String,
    val utxos: List<WalletUtxoDto>,
    val totalSats: Long,
    val count: Int
)

@Serializable
data class WalletUtxoDto(
    val txid: String,
    val vout: Int,
    val valueSats: Long,
    val address: String,
    val addressIndex: Int,
    val addressType: String,
    val confirmed: Boolean,
    val blockHeight: Int? = null,
    val blockTime: Long? = null
)

@Serializable
data class ReceiveAddressDto(
    val walletId: String,
    val address: String,
    val index: Int,
    val isNew: Boolean = true,
    val needsDerivation: Boolean = false
)

@Serializable
data class TransactionDetailDto(
    val txid: String,
    val version: Int = 2,
    val locktime: Int = 0,
    val size: Int = 0,
    val weight: Int = 0,
    val fee: Long = 0,
    val confirmed: Boolean = false,
    val blockHeight: Int? = null,
    val blockTime: Long? = null,
    val inputs: List<TxInputDto> = emptyList(),
    val outputs: List<TxOutputDto> = emptyList()
)

@Serializable
data class TxInputDto(
    val txid: String,
    val vout: Int,
    val address: String,
    val valueSats: Long,
    val isMine: Boolean = false
)

@Serializable
data class TxOutputDto(
    val index: Int,
    val address: String,
    val valueSats: Long,
    val isMine: Boolean = false
)

// ============ Price DTOs ============

@Serializable
data class FeeEstimatesDto(
    val fastestFee: Int,
    val halfHourFee: Int,
    val hourFee: Int,
    val economyFee: Int,
    val minimumFee: Int
)

@Serializable
data class BitcoinPricesDto(
    val czk: Double? = null,
    val usd: Double? = null,
    val eur: Double? = null,
    val czk24hChange: Double? = null,
    val usd24hChange: Double? = null,
    val eur24hChange: Double? = null,
    val lastUpdatedAt: Long? = null,
    val cachedAt: Long? = null
)

@Serializable
data class ConversionResultDto(
    val satoshis: Long,
    val btc: Double,
    val fiatValue: Double,
    val fiatCurrency: String
)
