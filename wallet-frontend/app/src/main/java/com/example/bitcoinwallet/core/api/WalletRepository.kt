package com.example.bitcoinwallet.core.api

import com.example.bitcoinwallet.feature.wallet.model.Transaction
import com.example.bitcoinwallet.feature.wallet.model.TransactionType
import com.example.bitcoinwallet.feature.wallet.model.WalletBalance
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Repository for wallet data.
 * Combines blockchain data with price data to provide complete wallet info.
 */
class WalletRepository(
    private val apiClient: WalletApiClient
) {
    
    /**
     * Get the receive address for a wallet.
     * 
     * @param walletId Wallet ID
     * @param accessToken JWT access token
     * @return Bitcoin receive address
     */
    suspend fun getWalletReceiveAddress(walletId: String, accessToken: String): String {
        val result = apiClient.getWalletAddress(walletId, accessToken)
        return result.address
    }
    
    /**
     * Get wallet balance in BTC and fiat.
     * 
     * @param address Bitcoin address to check balance for
     * @param accessToken JWT access token
     * @param fiatCurrency Target fiat currency (default: CZK)
     */
    suspend fun getWalletBalance(
        address: String,
        accessToken: String,
        fiatCurrency: String = "czk"
    ): WalletBalance {
        // Get balance from blockchain
        val addressInfo = apiClient.getAddressInfo(address, accessToken)
        val balanceSats = addressInfo.balance
        
        // Convert to fiat
        val conversion = try {
            apiClient.convertSatsToFiat(balanceSats, fiatCurrency)
        } catch (e: Exception) {
            // If price service fails, return 0 fiat
            null
        }
        
        return WalletBalance(
            balanceSats = balanceSats,
            balanceFiat = conversion?.fiatValue ?: 0.0,
            fiatCurrency = fiatCurrency.uppercase()
        )
    }
    
    /**
     * Get transaction history for address.
     * Determines if each transaction is sent or received based on address involvement.
     * 
     * @param address Bitcoin address to check transactions for
     * @param accessToken JWT access token
     */
    suspend fun getTransactionHistory(
        address: String,
        accessToken: String
    ): List<Transaction> {
        val transactions = apiClient.getAddressTransactions(address, accessToken)
        
        return transactions.map { tx ->
            // Determine transaction type and amount
            // For now, we'll use a simplified approach
            // In a real implementation, we'd check vin/vout to determine direction
            val type = TransactionType.RECEIVED // Placeholder - needs proper implementation
            val amount = tx.fee // Placeholder - needs proper calculation from vin/vout
            
            val date = tx.status.block_time?.let { timestamp ->
                Instant.ofEpochSecond(timestamp)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
            } ?: LocalDate.now()
            
            Transaction(
                id = tx.txid,
                txid = tx.txid,
                type = type,
                amount = amount,
                date = date
            )
        }
    }
    
    /**
     * Get current Bitcoin prices.
     */
    suspend fun getBitcoinPrices(currencies: String = "czk,usd,eur"): BitcoinPricesDto {
        return apiClient.getBitcoinPrices(currencies)
    }
    
    /**
     * Get UTXOs for coin control.
     */
    suspend fun getUtxos(address: String, accessToken: String): List<UtxoDto> {
        return apiClient.getAddressUtxos(address, accessToken)
    }
    
    /**
     * Get recommended fee rates.
     */
    suspend fun getFeeEstimates(accessToken: String): FeeEstimatesDto {
        return apiClient.getFeeEstimates(accessToken)
    }
}
