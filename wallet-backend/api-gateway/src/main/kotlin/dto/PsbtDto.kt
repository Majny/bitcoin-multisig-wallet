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

// ========== Nové DTO pro psbt-service ==========

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

@Serializable
data class CreatePsbtRequest(
    val walletId: String,
    val outputs: List<TxOutput>,
    val feeRate: Double,
    val utxos: List<UtxoSelection>? = null,
    val rbf: Boolean = true,
    val label: String? = null,
    val signerAccountIndex: Int? = null
)

@Serializable
data class HDNodeDto(
    val depth: Int,
    val fingerprint: Long,
    val child_num: Long,
    val chain_code: String,
    val public_key: String
)

@Serializable
data class TrezorConnectMultisigPubkey(
    val node: HDNodeDto,
    val address_n: List<Long>
)

@Serializable
data class TrezorConnectMultisig(
    val pubkeys: List<TrezorConnectMultisigPubkey>,
    val m: Int,
    val signatures: List<String> = emptyList()
)

@Serializable
data class TrezorConnectInput(
    val address_n: List<Long>,
    val prev_hash: String,
    val prev_index: Int,
    val amount: String,
    val script_type: String = "SPENDWITNESS",
    val sequence: Long = 0xFFFFFFFDL,
    val multisig: TrezorConnectMultisig? = null
)

@Serializable
data class TrezorConnectOutput(
    val address: String? = null,
    val address_n: List<Long>? = null,
    val amount: String,
    val script_type: String,
    val multisig: TrezorConnectMultisig? = null
)

@Serializable
data class TrezorConnectRefTx(
    val hash: String,
    val tx_hex: String
)

@Serializable
data class TrezorConnectParams(
    val coin: String,
    val inputs: List<TrezorConnectInput>,
    val outputs: List<TrezorConnectOutput>,
    val refTxs: List<TrezorConnectRefTx>? = null,
    val version: Int = 2,
    val locktime: Int = 0
)

@Serializable
data class BroadcastRawTxRequest(
    val txHex: String
)

@Serializable
data class AddTrezorSignaturesRequest(
    val signatures: List<String>,
    val cosignerIndex: Int,
    val fingerprint: String,
    val serializedTx: String? = null
)

@Serializable
data class CreatePsbtResponse(
    val id: String,
    val psbtBase64: String,
    val estimatedFee: Long,
    val estimatedVsize: Int,
    val trezorConnectParams: TrezorConnectParams? = null
)

@Serializable
data class SignatureInfo(
    val deviceId: String,
    val fingerprint: String,
    val signedAt: String
)

@Serializable
data class PsbtDetailResponse(
    val id: String,
    val walletId: String,
    val psbtBase64: String,
    val status: String,
    val requiredSigs: Int,
    val currentSigs: Int,
    val totalOutputSats: Long = 0,
    val estimatedFeeSats: Long = 0,
    val signatures: List<SignatureInfo> = emptyList(),
    val label: String? = null,
    val txid: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val trezorConnectParams: TrezorConnectParams? = null
)

@Serializable
data class PsbtListResponse(
    val psbts: List<PsbtDetailResponse>
)

@Serializable
data class AddSignatureRequest(
    val psbtBase64: String,
    val deviceId: String,
    val fingerprint: String
)

@Serializable
data class CombinePsbtsRequest(
    val psbts: List<String>
)

@Serializable
data class FinalizeResponse(
    val psbtId: String,
    val txHex: String,
    val txid: String
)

@Serializable
data class BroadcastResponse(
    val psbtId: String,
    val txid: String,
    val success: Boolean
)

// ========== Signers endpoint ==========

@Serializable
data class SignerStatusResponse(
    val psbtId: String,
    val walletId: String,
    val status: String,
    val requiredSigs: Int,
    val currentSigs: Int,
    val signers: List<SignerDetail>
)

@Serializable
data class SignerDetail(
    val fingerprint: String,
    val cosignerIndex: Int,
    val signed: Boolean,
    val deviceId: String? = null,
    val signedAt: String? = null
)
