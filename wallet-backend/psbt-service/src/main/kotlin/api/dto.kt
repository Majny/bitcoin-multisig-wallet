package cz.majny.wallet.psbt.api

import kotlinx.serialization.Serializable

// ========== Request DTOs ==========

/**
 * Request pro vytvoření nové PSBT transakce.
 */
@Serializable
data class CreatePsbtRequest(
    val walletId: String,
    val outputs: List<TxOutput>,
    val feeRate: Double,                    // sats/vB
    val utxos: List<UtxoSelection>? = null, // pokud null, automatický výběr
    val rbf: Boolean = true,                // Replace-by-fee enabled
    val label: String? = null
)

@Serializable
data class TxOutput(
    val address: String,
    val amountSats: Long
)

@Serializable
data class UtxoSelection(
    val txid: String,
    val vout: Int
)

/**
 * Request pro přidání podpisu k existující PSBT.
 */
@Serializable
data class AddSignatureRequest(
    val psbtBase64: String,     // PSBT s novým podpisem
    val deviceId: String,
    val fingerprint: String
)

/**
 * Request pro kombinaci více PSBT (pro offline signing workflow).
 */
@Serializable
data class CombinePsbtsRequest(
    val psbts: List<String>     // seznam PSBT v base64
)

// ========== Response DTOs ==========

@Serializable
data class PsbtResponse(
    val id: String,
    val walletId: String,
    val psbtBase64: String,
    val status: String,
    val requiredSigs: Int,
    val currentSigs: Int,
    val signatures: List<SignatureInfo> = emptyList(),
    val label: String? = null,
    val txid: String? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class SignatureInfo(
    val fingerprint: String,
    val deviceId: String,
    val signedAt: String
)

@Serializable
data class PsbtListResponse(
    val psbts: List<PsbtResponse>
)

@Serializable
data class CreatePsbtResponse(
    val id: String,
    val psbtBase64: String,
    val estimatedFee: Long,
    val estimatedVsize: Int
)

@Serializable
data class FinalizeResponse(
    val psbtId: String,
    val txHex: String,          // finální raw transakce připravená k broadcastu
    val txid: String
)

@Serializable
data class BroadcastResponse(
    val psbtId: String,
    val txid: String,
    val success: Boolean
)

// ========== Internal DTOs (pro komunikaci s jinými službami) ==========

@Serializable
data class UtxoDto(
    val txid: String,
    val vout: Int,
    val value: Long,
    val status: UtxoStatusDto
)

@Serializable
data class UtxoStatusDto(
    val confirmed: Boolean,
    val block_height: Int? = null
)

@Serializable
data class WalletDetailDto(
    val walletId: String,
    val network: String,
    val type: String,               // "single" or "multisig"
    val scriptType: String,         // "p2wpkh", "p2tr", "p2wsh"
    val m: Int? = null,             // pro multisig: počet požadovaných podpisů
    val n: Int? = null,             // pro multisig: celkový počet cosignerů
    val receiveDescriptor: String,
    val changeDescriptor: String,
    val cosigners: List<CosignerDto> = emptyList()
)

@Serializable
data class CosignerDto(
    val idx: Int,
    val fingerprint: String,
    val originPath: String,
    val xpubRoot: String
)

@Serializable
data class AddressDto(
    val walletId: String,
    val address: String,
    val index: Int,
    val type: String
)
