package cz.majny.wallet.gateway.dto

import kotlinx.serialization.Serializable



@Serializable
data class RegistryImportWalletRequest(
    val fingerprint: String,
    val xpub: String,
    val derivationPath: String,
    val deviceModel: String? = null,
    val deviceLabel: String? = null
)






@Serializable
data class MemberAttach(
    val deviceId: String,
    val cosignerIdx: Int? = null
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


fun RegistryWalletSummary.toGatewayWalletSummary(): WalletSummarySerializable =
    WalletSummarySerializable(
        id = walletId,
        label = label ?: walletId,
        type = type,
        balanceSats = 0L // TODO: placeholder
    )



@Serializable
data class UpsertDeviceRequest(
    val deviceId: String,
    val fingerprint: String,
    val model: String? = null,
    val label: String? = null
)

@Serializable
data class UpsertDeviceResponse(
    val deviceId: String,
    val fingerprint: String,
    val model: String? = null,
    val label: String? = null
)

@Serializable
data class RegistryWalletSummary(
    val walletId: String,
    val network: String,
    val type: String,
    val scriptType: String,
    val m: Int? = null,
    val n: Int? = null,
    val label: String? = null
)
