package com.example.bitcoinwallet.core.api

import com.example.bitcoinwallet.core.session.SessionStore
import com.example.bitcoinwallet.feature.wallet.model.Transaction
import com.example.bitcoinwallet.feature.wallet.model.TransactionType
import com.example.bitcoinwallet.feature.wallet.model.WalletBalance
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * Domain-shaped facade over WalletApiClient. Maps backend DTOs to the
 * UI's model types (WalletBalance, Transaction) and folds in fiat
 * conversion so callers don't need to know about price-service. Direct
 * passthroughs are kept too — viewmodels that don't need mapping use
 * those.
 */
class WalletRepository(
    private val apiClient: WalletApiClient
) {

    /*
     * Aggregated wallet balance + fiat conversion. Falls back to 0 fiat
     * if price-service is unreachable rather than failing the dashboard
     * load — the sats balance is what actually matters.
     */
    suspend fun getWalletBalance(
        walletId: String,
        accessToken: String,
        fiatCurrency: String = SessionStore.preferredCurrency.value
    ): WalletBalance {
        val balanceDto = apiClient.getWalletBalance(walletId, accessToken)
        val balanceSats = balanceDto.totalSats

        val conversion = try {
            apiClient.convertSatsToFiat(accessToken, balanceSats, fiatCurrency)
        } catch (e: Exception) {
            null
        }

        return WalletBalance(
            balanceSats = balanceSats,
            balanceFiat = conversion?.fiatValue ?: 0.0,
            fiatCurrency = fiatCurrency.uppercase()
        )
    }

    /*
     * Tx history mapped to the UI Transaction model. Explorer-service
     * already pre-classifies SENT/RECEIVED with the correct net amount,
     * so this is a straight projection.
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

            // Unconfirmed txs have no blockTime — push them to a sentinel
            // far-future date so they sort to the top of the history list.
            val dateTime = tx.blockTime?.let { timestamp ->
                Instant.ofEpochSecond(timestamp)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime()
            } ?: LocalDateTime.of(9999, 12, 31, 23, 59)

            Transaction(
                id = tx.txid,
                txid = tx.txid,
                type = type,
                amount = tx.amountSats,
                dateTime = dateTime,
                confirmed = tx.confirmed,
                confirmations = tx.confirmations
            )
        }
    }

    /* First unused receive address — backend handles gap-limit derivation. */
    suspend fun getReceiveAddress(walletId: String, accessToken: String): String {
        val dto = apiClient.getReceiveAddress(walletId, accessToken)
        return dto.address
    }

    /* Wallet UTXOs (passthrough) — coin control screen consumes the DTO directly. */
    suspend fun getWalletUtxos(walletId: String, accessToken: String): WalletUtxosDto {
        return apiClient.getWalletUtxos(walletId, accessToken)
    }

    /* Current BTC prices (passthrough). */
    suspend fun getBitcoinPrices(accessToken: String, currencies: String = "czk,usd,eur"): BitcoinPricesDto {
        return apiClient.getBitcoinPrices(accessToken, currencies)
    }

    /* Current fee recommendations (passthrough). */
    suspend fun getFeeEstimates(accessToken: String): FeeEstimatesDto {
        return apiClient.getFeeEstimates(accessToken)
    }

    /* Detailed tx with inputs/outputs (passthrough). */
    suspend fun getTransactionDetail(
        txid: String,
        accessToken: String,
        walletId: String? = null
    ): TransactionDetailDto {
        return apiClient.getTransactionDetail(txid, accessToken, walletId)
    }
}
