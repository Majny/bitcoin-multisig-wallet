package cz.majny.wallet.gateway.dto

import kotlinx.serialization.Serializable

@Serializable
data class CoinSelectionInput(val txid: String, val vout: Int)

@Serializable
data class PreparePsbtBackendRequest(
    val amountSats: Long,
    val destinationAddress: String,
    val feeRateSatsPerVb: Long? = null,
    val selectedInputs: List<CoinSelectionInput> = emptyList()
)

@Serializable
data class PreparePsbtResponse(
    val psbtId: String,
    val psbtBase64: String
)

@Serializable
data class SubmitSignedPsbtBackendRequest(
    val psbtId: String,
    val signedPsbtBase64: String
)

@Serializable
data class SubmitSignedPsbtBackendResponse(
    val status: String,
    val txId: String? = null
)
