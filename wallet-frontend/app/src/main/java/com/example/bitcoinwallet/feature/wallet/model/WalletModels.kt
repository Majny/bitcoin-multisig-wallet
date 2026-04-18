package com.example.bitcoinwallet.feature.wallet.model

import java.time.LocalDateTime

/**
 * Represents a single transaction in history.
 */
data class Transaction(
    val id: String,
    val txid: String,
    val type: TransactionType,
    val amount: Long, // in satoshis
    val dateTime: LocalDateTime,
    val confirmed: Boolean = true,
    val confirmations: Int = 6
)

enum class TransactionType {
    SENT,
    RECEIVED
}

/**
 * Wallet balance information.
 */
data class WalletBalance(
    val balanceSats: Long, // Balance in satoshis
    val balanceFiat: Double, // Balance in fiat currency
    val fiatCurrency: String = "CZK"
) {
    val balanceBtc: Double get() = balanceSats / 100_000_000.0
    
    fun formatBtc(): String = String.format(java.util.Locale.US, "%.8f BTC", balanceBtc)

    fun formatFiat(): String {
        // USD/EUR commonly carry two-decimal precision (e.g. 3849.23),
        // CZK amounts at BTC scale are whole crowns so fractions add noise.
        val decimals = if (fiatCurrency.equals("CZK", ignoreCase = true)) 0 else 2
        return String.format("≈ %,.${decimals}f %s", balanceFiat, fiatCurrency)
    }
}
