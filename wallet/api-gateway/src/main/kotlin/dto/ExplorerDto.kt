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
