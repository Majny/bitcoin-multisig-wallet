package com.example.bitcoinwallet.feature.wallet.model

import java.time.LocalDate

/**
 * Represents a single transaction in history.
 */
data class Transaction(
    val id: String,
    val txid: String,
    val type: TransactionType,
    val amount: Long, // in satoshis
    val date: LocalDate,
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
    
    fun formatBtc(): String = String.format("%.8f BTC", balanceBtc)
    
    fun formatFiat(): String = String.format("≈ %,.0f %s", balanceFiat, fiatCurrency)
}
