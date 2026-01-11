package cz.majny.wallet.gateway.dto

import kotlinx.serialization.Serializable

@Serializable
data class TrezorLoginRequest(
    val fingerprint: String,
    val xpub: String,
    val derivationPath: String,
    val deviceModel: String? = null,
    val deviceLabel: String? = null
)

@Serializable
data class WalletSummarySerializable(
    val id: String,
    val label: String,
    val type: String,
    val balanceSats: Long
)

@Serializable
data class UserSummarySerializable(
    val id: String,
    val displayName: String,
    val trezorFingerprint: String,
    val wallets: List<WalletSummarySerializable>
)

@Serializable
data class TrezorLoginResponse(
    val accessToken: String,
    val refreshToken: String,
    val user: UserSummarySerializable
)

@Serializable
data class RefreshTokenRequest(val refreshToken: String)

@Serializable
data class RefreshTokenResponse(
    val accessToken: String,
    val refreshToken: String
)
