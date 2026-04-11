package cz.majny.wallet.psbt.api

import kotlinx.serialization.Serializable

// ========== Request DTOs ==========

/* Request for creating a new PSBT transaction. */
@Serializable
data class CreatePsbtRequest(
    val walletId: String,
    val outputs: List<TxOutput>,
    val feeRate: Double,                    // sats/vB
    val utxos: List<UtxoSelection>? = null, // if null, auto-select UTXOs
    val rbf: Boolean = true,                // Replace-by-fee enabled (BIP-125)
    val label: String? = null,
    val signerAccountIndex: Int? = null      // BIP-48 account index of the signing cosigner (for multisig TrezorConnect params)
)

@Serializable
data class TxOutput(
    val address: String,
    val amountSats: Long
)

@Serializable
data class UtxoSelection(
    val txid: String,
    val vout: Int,
    val address: String? = null  // if provided, skips full wallet scan
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
data class SignatureInfo(
    val fingerprint: String,
    val deviceId: String,
    val cosignerIndex: Int = 0,
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
    val estimatedVsize: Int,
    val trezorConnectParams: TrezorConnectParams? = null,
    val signerCosignerIndex: Int = 0
)

@Serializable
data class BroadcastResponse(
    val psbtId: String,
    val txid: String,
    val success: Boolean
)

// ========== Signers Endpoint ==========

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
    val originPath: String? = null,
    val xpub: String? = null,
    val signed: Boolean,
    val deviceId: String? = null,
    val signedAt: String? = null,
    val label: String? = null
)

// ========== Trezor Connect DTOs ==========

/*
 * HD node structure for Trezor Connect.
 * Represents a single cosigner's account-level public key.
 */
@Serializable
data class HDNodeDto(
    val depth: Int,
    val fingerprint: Long,
    val child_num: Long,
    val chain_code: String,
    val public_key: String
)

/*
 * Multisig pubkey entry for Trezor Connect.
 * node = cosigner's account-level HD node.
 * address_n = [chain, index] relative to the account xpub.
 */
@Serializable
data class TrezorConnectMultisigPubkey(
    val node: HDNodeDto,
    val address_n: List<Long>
)

/*
 * Multisig metadata for Trezor Connect.
 * Every multisig input/output must include this object.
 * signatures = existing signatures (empty string = not yet signed).
 */
@Serializable
data class TrezorConnectMultisig(
    val pubkeys: List<TrezorConnectMultisigPubkey>,
    val m: Int,
    val signatures: List<String> = emptyList()
)

/*
 * Trezor Connect signTransaction input.
 * address_n = full BIP-32 derivation path as uint32 array (hardened = index | 0x80000000).
 * For multisig: includes multisig object with all cosigner pubkeys and threshold.
 */
@Serializable
data class TrezorConnectInput(
    val address_n: List<Long>,
    val prev_hash: String,
    val prev_index: Int,
    val amount: String,
    val script_type: String = "SPENDWITNESS",
    val sequence: Long = 0xFFFFFFFDL,  // RBF signaling (BIP-125)
    val multisig: TrezorConnectMultisig? = null
)

/*
 * Trezor Connect signTransaction output.
 * External output: address + amount + script_type="PAYTOADDRESS"
 * Change output:   address_n + amount + script_type="PAYTOWITNESS"
 * For multisig change: includes multisig object with all cosigner pubkeys.
 */
@Serializable
data class TrezorConnectOutput(
    val address: String? = null,
    val address_n: List<Long>? = null,
    val amount: String,
    val script_type: String,
    val multisig: TrezorConnectMultisig? = null
)

/*
 * Reference transaction for Trezor Connect (structured format).
 * Trezor Connect deeplink does NOT support raw tx_hex — it needs parsed fields:
 * version, inputs (with script_sig), bin_outputs (with amount + script_pubkey), lock_time.
 */
@Serializable
data class TrezorConnectRefTx(
    val hash: String,
    val version: Int,
    val lock_time: Int,
    val inputs: List<TrezorConnectRefTxInput>,
    val bin_outputs: List<TrezorConnectRefTxBinOutput>
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

/* Complete Trezor Connect signTransaction parameters. */
@Serializable
data class TrezorConnectParams(
    val coin: String,
    val inputs: List<TrezorConnectInput>,
    val outputs: List<TrezorConnectOutput>,
    val refTxs: List<TrezorConnectRefTx>? = null,
    val version: Int = 2,       // tx version (2 = SegWit/RBF)
    val locktime: Int = 0
)

/* Request for broadcasting a raw signed transaction hex (from Trezor Connect serializedTx). */
@Serializable
data class BroadcastRawTxRequest(
    val txHex: String
)

/*
 * Request for adding Trezor Connect signatures to a multisig PSBT.
 * signatures = DER-encoded hex signatures (one per input) from Trezor Connect response.
 * cosignerIndex = index of the cosigner who signed.
 */
@Serializable
data class AddTrezorSignaturesRequest(
    val signatures: List<String>,
    val cosignerIndex: Int = 0,
    val fingerprint: String,
    val serializedTx: String? = null,
    val signerAccountIndex: Int? = null
)

// ========== Internal DTOs (communication with other services) ==========

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
    val type: String,               // "SINGLE_SIG" or "MULTI_SIG"
    val scriptType: String,         // "WPKH", "WSH", "TR"
    val m: Int? = null,             // for multisig: required signature count
    val n: Int? = null,             // for multisig: total cosigner count
    val receiveDescriptor: String,
    val changeDescriptor: String,
    val cosigners: List<CosignerDto> = emptyList()
)

@Serializable
data class CosignerDto(
    val idx: Int,
    val fingerprint: String,
    val originPath: String,
    val xpubRoot: String,
    val label: String? = null
)

@Serializable
data class AddressDto(
    val walletId: String,
    val address: String,
    val index: Int,
    val type: String
)
