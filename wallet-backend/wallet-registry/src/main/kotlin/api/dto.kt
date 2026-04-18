package cz.majny.wallet.registry.api

import kotlinx.serialization.Serializable

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
    val xpubRoot: String,
    val label: String? = null
)

@Serializable
data class UpdateCosignerLabelRequest(
    val label: String,
    val deviceId: String
)

@Serializable
data class CosignerLabelEntry(
    val idx: Int,
    val label: String
)

@Serializable
data class CosignerLabelsResponse(
    val walletId: String,
    val deviceId: String,
    val labels: List<CosignerLabelEntry>
)

@Serializable
data class MemberAttach(
    val deviceId: String,
    val accountIndex: Int = -1
)

@Serializable
data class WalletSummary(
    val walletId: String,
    val network: String,
    val type: String,
    val scriptType: String,
    val m: Int? = null,
    val n: Int? = null,
    val label: String? = null,
    val accountIndex: Int? = null,
    /** For multisig: the BIP-48 account from wallets table (maps to cosigner position). */
    val cosignerAccountIndex: Int? = null
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

@Serializable
data class DeriveAddressesRequest(
    val descriptor: String,
    val network: String,
    val count: Int = 5
)

@Serializable
data class DeriveAddressesResponse(
    val addresses: List<String>
)

@Serializable
data class DeriveAdditionalAddressRequest(
    val type: String,   // "receive" or "change"
    val index: Int
)
