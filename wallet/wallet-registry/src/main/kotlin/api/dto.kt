package cz.majny.wallet.registry.api

import kotlinx.serialization.Serializable

@Serializable
data class UpsertDeviceRequest(
    val deviceId: String,
    val fingerprint: String,
    val model: String? = null,
    val label: String? = null
)

@Serializable
data class DeviceResponse(
    val deviceId: String,
    val fingerprint: String,
    val model: String? = null,
    val label: String? = null
)

@Serializable
data class CreateWalletRequest(
    val walletId: String,
    val network: String,
    val type: String,
    val scriptType: String,
    val m: Int? = null,
    val n: Int? = null,
    val accountIndex: Int = 0,
    val birthHeight: Int? = null,
    val label: String? = null,
    val receiveDescriptor: String,
    val changeDescriptor: String,
    val cosigners: List<CosignerInWallet> = emptyList(),
    val members: List<MemberAttach> = emptyList()
)

@Serializable
data class CosignerInWallet(
    val idx: Int,
    val cosignerId: String,
    val fingerprint: String,
    val originPath: String,
    val xpubRoot: String
)

@Serializable
data class MemberAttach(
    val deviceId: String,
    val cosignerIdx: Int? = null
)

@Serializable
data class WalletSummary(
    val walletId: String,
    val network: String,
    val type: String,
    val scriptType: String,
    val m: Int? = null,
    val n: Int? = null,
    val label: String? = null
)

@Serializable
data class WalletDetail(
    val walletId: String,
    val network: String,
    val type: String,
    val scriptType: String,
    val m: Int? = null,
    val n: Int? = null,
    val accountIndex: Int,
    val birthHeight: Int? = null,
    val label: String? = null,
    val receiveDescriptor: String,
    val changeDescriptor: String,
    val cosigners: List<CosignerInWallet> = emptyList(),
    val members: List<MemberAttach> = emptyList()
)

@Serializable
data class ErrorResponse(
    val error: String
)
