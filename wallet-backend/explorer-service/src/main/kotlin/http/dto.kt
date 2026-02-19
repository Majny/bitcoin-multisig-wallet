package cz.majny.wallet.explorer.http

import kotlinx.serialization.Serializable

// ============ Wallet Balance ============

@Serializable
data class WalletBalanceResponse(
    val walletId: String,
    val confirmedSats: Long,
    val unconfirmedSats: Long,
    val totalSats: Long,
    val utxoCount: Int,
    val addressCount: Int
)

// ============ Wallet Transactions ============

@Serializable
data class WalletTransactionsResponse(
    val walletId: String,
    val transactions: List<WalletTransaction>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

@Serializable
data class WalletTransaction(
    val txid: String,
    val type: String,           // "SENT" or "RECEIVED"
    val amountSats: Long,       // Always positive
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

// ============ Wallet UTXOs ============

@Serializable
data class WalletUtxosResponse(
    val walletId: String,
    val utxos: List<WalletUtxo>,
    val totalSats: Long,
    val count: Int
)

@Serializable
data class WalletUtxo(
    val txid: String,
    val vout: Int,
    val valueSats: Long,
    val address: String,
    val addressIndex: Int,
    val addressType: String,    // "receive" or "change"
    val confirmed: Boolean,
    val blockHeight: Int? = null,
    val blockTime: Long? = null
)

// ============ Receive Address ============

@Serializable
data class ReceiveAddressResponse(
    val walletId: String,
    val address: String,
    val index: Int,
    val isNew: Boolean = true,
    val needsDerivation: Boolean = false
)

// ============ Transaction Detail ============

@Serializable
data class TransactionDetailResponse(
    val txid: String,
    val version: Int,
    val locktime: Int,
    val size: Int,
    val weight: Int,
    val fee: Long,
    val confirmed: Boolean,
    val blockHeight: Int? = null,
    val blockTime: Long? = null,
    val inputs: List<TransactionInput>,
    val outputs: List<TransactionOutput>
)

@Serializable
data class TransactionInput(
    val txid: String,
    val vout: Int,
    val address: String,
    val valueSats: Long,
    val isMine: Boolean = false
)

@Serializable
data class TransactionOutput(
    val index: Int,
    val address: String,
    val valueSats: Long,
    val isMine: Boolean = false
)
