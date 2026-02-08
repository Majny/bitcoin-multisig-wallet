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
 * API client for wallet data - balance, transactions, prices.
 * Communicates with API Gateway which routes to blockchain-service and price-service.
 */
class WalletApiClient(
    private val baseUrl: String,
    private val client: HttpClient = defaultClient()
) {
    
    // ============ Blockchain Endpoints ============
    
    /**
     * GET /api/v1/wallets/{walletId}/address
     * Get the receive address for a wallet.
     */
    suspend fun getWalletAddress(walletId: String, accessToken: String): WalletAddressDto {
        return client.get("$baseUrl/wallets/$walletId/address") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }
    
    /**
     * GET /api/v1/blockchain/address/{address}
     * Get address info including balance and tx count.
     */
    suspend fun getAddressInfo(address: String, accessToken: String): AddressInfoDto {
        return client.get("$baseUrl/blockchain/address/$address") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }
    
    /**
     * GET /api/v1/blockchain/address/{address}/utxos
     * Get UTXOs for coin control.
     */
    suspend fun getAddressUtxos(address: String, accessToken: String): List<UtxoDto> {
        return client.get("$baseUrl/blockchain/address/$address/utxos") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }
    
    /**
     * GET /api/v1/blockchain/address/{address}/txs
     * Get transaction history for address.
     */
    suspend fun getAddressTransactions(address: String, accessToken: String): List<TransactionDto> {
        return client.get("$baseUrl/blockchain/address/$address/txs") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }
    
    /**
     * GET /api/v1/blockchain/fees
     * Get recommended fee rates.
     */
    suspend fun getFeeEstimates(accessToken: String): FeeEstimatesDto {
        return client.get("$baseUrl/blockchain/fees") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }
    
    // ============ Price Endpoints ============
    
    /**
     * GET /api/v1/price
     * Get current Bitcoin prices in various currencies.
     */
    suspend fun getBitcoinPrices(currencies: String = "czk,usd,eur"): BitcoinPricesDto {
        return client.get("$baseUrl/price") {
            parameter("currencies", currencies)
        }.body()
    }
    
    /**
     * GET /api/v1/price/convert
     * Convert satoshis to fiat value.
     */
    suspend fun convertSatsToFiat(sats: Long, currency: String = "czk"): ConversionResultDto {
        return client.get("$baseUrl/price/convert") {
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

// ============ DTOs ============

@Serializable
data class WalletAddressDto(
    val walletId: String,
    val address: String,
    val index: Int = 0,
    val type: String = "receive"
)

@Serializable
data class AddressInfoDto(
    val address: String,
    val txCount: Int,
    val balance: Long
)

@Serializable
data class UtxoDto(
    val txid: String,
    val vout: Int,
    val value: Long,
    val status: UtxoStatusDto
)

@Serializable
data class UtxoStatusDto(
    val confirmed: Boolean,
    val block_height: Int? = null,
    val block_hash: String? = null,
    val block_time: Long? = null
)

@Serializable
data class TransactionDto(
    val txid: String,
    val version: Int = 2,
    val locktime: Int = 0,
    val size: Int = 0,
    val weight: Int = 0,
    val fee: Long = 0,
    val status: TxStatusDto = TxStatusDto()
)

@Serializable
data class TxStatusDto(
    val confirmed: Boolean = false,
    val block_height: Int? = null,
    val block_hash: String? = null,
    val block_time: Long? = null
)

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
