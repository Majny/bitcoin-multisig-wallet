package cz.majny.wallet.gateway.dto

import kotlinx.serialization.Serializable

@Serializable
data class SignPsbtRequest(val psbtBase64: String)

@Serializable
data class SignPsbtResponse(val signedPsbtBase64: String)
