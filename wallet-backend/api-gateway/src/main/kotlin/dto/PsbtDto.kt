/* DTOs for the PSBT surface. Mostly straight pass-through to psbt-service,
 * with the bigger TrezorConnect* group mirroring the parameter shape that
 * Trezor Connect expects on the device side. */
package cz.majny.wallet.gateway.dto

import kotlinx.serialization.Serializable

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
    // Trezor master fingerprint of the signing device — gateway fills this in
    // from the JWT before forwarding to psbt-service so multisig cosigner
    // resolution does not collapse when two devices share an account index.
    val signerFingerprint: String? = null,
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
data class TrezorConnectRefTxInput(
    val prev_hash: String,
    val prev_index: Long,
    val script_sig: String,
    val sequence: Long
)

@Serializable
data class TrezorConnectRefTxBinOutput(
    val amount: Long,
    val script_pubkey: String
)

@Serializable
data class TrezorConnectRefTx(
    val hash: String,
    val version: Int,
    val lock_time: Int,
    val inputs: List<TrezorConnectRefTxInput>,
    val bin_outputs: List<TrezorConnectRefTxBinOutput>
)

/* Bundle of params Trezor Connect needs to sign a transaction. Inputs include
 * derivation paths so the device picks the right key; outputs may include
 * derivation paths too so the device recognises change as its own. refTxs
 * carries previous transactions in parsed form, required by Trezor firmware
 * 2.4+ even for native-segwit inputs (it independently re-derives input
 * amounts to defend against fee-spoofing). */
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
data class VerifyAddressResponse(
    val path: List<Long>,
    val coin: String,
    val scriptType: String,
    val showOnTrezor: Boolean = true,
    val multisig: TrezorConnectMultisig? = null
)

@Serializable
data class BroadcastRawTxRequest(
    val txHex: String
)

@Serializable
data class AddTrezorSignaturesRequest(
    val signatures: List<String>,
    val cosignerIndex: Int = 0,
    val fingerprint: String,
    val serializedTx: String? = null,
    val signerAccountIndex: Int? = null
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
    val cosignerIndex: Int = 0,
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
    val trezorConnectParams: TrezorConnectParams? = null,
    val serializedTx: String? = null
)

@Serializable
data class PsbtListResponse(
    val psbts: List<PsbtDetailResponse>
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
    val signedAt: String? = null,
    val originPath: String? = null,
    val xpub: String? = null,
    val label: String? = null
)
