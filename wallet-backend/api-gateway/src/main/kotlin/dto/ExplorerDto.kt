package cz.majny.wallet.gateway.dto

import kotlinx.serialization.Serializable

@Serializable
data class UtxoDto(
    val txid: String,
    val vout: Int,
    val valueSats: Long,
    val address: String? = null,
    val confirmations: Int? = null
)

// ============ Explorer Service DTOs ============

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
    val type: String,
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
    val isNew: Boolean = true
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

@Serializable
data class FeeEstimatesExplorerDto(
    val fastestFee: Int,
    val halfHourFee: Int,
    val hourFee: Int,
    val economyFee: Int,
    val minimumFee: Int
)
