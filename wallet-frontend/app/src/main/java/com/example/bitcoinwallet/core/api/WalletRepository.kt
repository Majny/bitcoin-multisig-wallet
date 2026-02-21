package com.example.bitcoinwallet.core.api

import com.example.bitcoinwallet.core.session.SessionStore
import com.example.bitcoinwallet.feature.wallet.model.Transaction
import com.example.bitcoinwallet.feature.wallet.model.TransactionType
import com.example.bitcoinwallet.feature.wallet.model.WalletBalance
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Repository for wallet data.
 * Uses explorer-service (via gateway) for wallet-level aggregated data
 * and price-service for fiat conversions.
 */
class WalletRepository(
    private val apiClient: WalletApiClient
) {
    
    /**
     * Get wallet balance (aggregated across all addresses) with fiat conversion.
     *
     * @param walletId Wallet ID
     * @param accessToken JWT access token
     * @param fiatCurrency Target fiat currency (default: CZK)
     */
    suspend fun getWalletBalance(
        walletId: String,
        accessToken: String,
        fiatCurrency: String = SessionStore.preferredCurrency
    ): WalletBalance {
        // Explorer-service aggregates balance across all wallet addresses
        val balanceDto = apiClient.getWalletBalance(walletId, accessToken)
        val balanceSats = balanceDto.totalSats
        
        // Convert to fiat via price-service
        val conversion = try {
            apiClient.convertSatsToFiat(accessToken, balanceSats, fiatCurrency)
        } catch (e: Exception) {
            // If price service is unavailable, show 0 fiat
            null
        }
        
        return WalletBalance(
            balanceSats = balanceSats,
            balanceFiat = conversion?.fiatValue ?: 0.0,
            fiatCurrency = fiatCurrency.uppercase()
        )
    }
    
    /**
     * Get transaction history for the entire wallet.
     * Explorer-service already classifies each tx as SENT/RECEIVED with correct amount.
     *
     * @param walletId Wallet ID
     * @param accessToken JWT access token
     * @param limit Max transactions to fetch
     * @param offset Pagination offset
     */
    suspend fun getTransactionHistory(
        walletId: String,
        accessToken: String,
        limit: Int = 50,
        offset: Int = 0
    ): List<Transaction> {
        val response = apiClient.getWalletTransactions(walletId, accessToken, limit, offset)
        
        return response.transactions.map { tx ->
            val type = when (tx.type) {
                "SENT" -> TransactionType.SENT
                else   -> TransactionType.RECEIVED
            }
            
            val date = tx.blockTime?.let { timestamp ->
                Instant.ofEpochSecond(timestamp)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
            } ?: LocalDate.now()
            
            Transaction(
                id = tx.txid,
                txid = tx.txid,
                type = type,
                amount = tx.amountSats,
                date = date,
                confirmed = tx.confirmed,
                confirmations = tx.confirmations
            )
        }
    }
    
    /**
     * Get the first unused receive address for a wallet.
     *
     * @param walletId Wallet ID
     * @param accessToken JWT access token
     * @return Bitcoin receive address string
     */
    suspend fun getReceiveAddress(walletId: String, accessToken: String): String {
        val dto = apiClient.getReceiveAddress(walletId, accessToken)
        return dto.address
    }
    
    /**
     * Get all UTXOs for the wallet (coin control).
     */
    suspend fun getWalletUtxos(walletId: String, accessToken: String): WalletUtxosDto {
        return apiClient.getWalletUtxos(walletId, accessToken)
    }
    
    /**
     * Get current Bitcoin prices.
     */
    suspend fun getBitcoinPrices(accessToken: String, currencies: String = "czk,usd,eur"): BitcoinPricesDto {
        return apiClient.getBitcoinPrices(accessToken, currencies)
    }
    
    /**
     * Get recommended fee rates.
     */
    suspend fun getFeeEstimates(accessToken: String): FeeEstimatesDto {
        return apiClient.getFeeEstimates(accessToken)
    }
    
    /**
     * Get detailed transaction info (inputs/outputs).
     */
    suspend fun getTransactionDetail(
        txid: String,
        accessToken: String,
        walletId: String? = null
    ): TransactionDetailDto {
        return apiClient.getTransactionDetail(txid, accessToken, walletId)
    }
}
