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

// ========== Wallet Import DTOs ==========

/**
 * Gateway request for importing a wallet via descriptor.
 * The gateway enriches this with device info from JWT before forwarding to registry.
 */
@Serializable
data class ImportWalletFromAppRequest(
    val descriptor: String,
    val network: String = "mainnet",
    val label: String? = null,
    val birthHeight: Int? = null,
    val accountIndex: Int? = null
)

/**
 * Request forwarded to wallet-registry's /wallets/import endpoint.
 */
@Serializable
data class ImportWalletGatewayRequest(
    val descriptor: String,
    val network: String = "mainnet",
    val label: String? = null,
    val birthHeight: Int? = null,
    val deviceId: String? = null,
    val deviceFingerprint: String? = null,
    val accountIndex: Int? = null
)

/**
 * Response from wallet-registry's /wallets/import endpoint.
 */
@Serializable
data class ImportWalletGatewayResponse(
    val success: Boolean,
    val walletId: String? = null,
    val isNew: Boolean = false,
    val error: String? = null,
    val wallet: WalletDetail? = null
)






@Serializable
data class MemberAttach(
    val deviceId: String,
    val accountIndex: Int = -1
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
    val label: String? = null,
    val accountIndex: Int? = null
)

// ---- Address DTOs (mirrors wallet-registry API) ----

@Serializable
data class WalletAddressResponse(
    val walletId: String,
    val address: String,
    val index: Int,
    val type: String    // "receive" or "change"
)

@Serializable
data class WalletAddressesResponse(
    val walletId: String,
    val addresses: List<WalletAddressResponse>
)
