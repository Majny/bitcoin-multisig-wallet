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
    val label: String? = null,
    val signerAccountIndex: Int? = null      // BIP-48 account index podepisujícího cosignera (pro multisig TrezorConnect params)
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
    val originPath: String? = null,
    val xpub: String? = null,
    val signed: Boolean,
    val deviceId: String? = null,
    val signedAt: String? = null
)

// ========== Trezor Connect DTOs ==========

/**
 * Multisig pubkey entry pro Trezor Connect.
 * node = xpub string cosignera (account-level, e.g. "xpub6...")
 * address_n = [chain, index] relativni k account xpub
 */
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

/**
 * Multisig metadata pro Trezor Connect.
 * Kazdy input/output multisig transakce musi mit tento objekt.
 * signatures = existujici podpisy (prazdny string = jeste nepodepsano).
 */
@Serializable
data class TrezorConnectMultisig(
    val pubkeys: List<TrezorConnectMultisigPubkey>,
    val m: Int,
    val signatures: List<String> = emptyList()
)

/**
 * Trezor Connect signTransaction input.
 * address_n = full BIP-32 derivation path jako uint32 array (hardened = index | 0x80000000).
 * Pro multisig: multisig objekt s pubkeys a threshold.
 */
@Serializable
data class TrezorConnectInput(
    val address_n: List<Long>,
    val prev_hash: String,
    val prev_index: Int,
    val amount: String,
    val script_type: String = "SPENDWITNESS",
    val sequence: Long = 0xFFFFFFFDL,  // RBF signaling (BIP125)
    val multisig: TrezorConnectMultisig? = null
)

/**
 * Trezor Connect signTransaction output.
 * External output: address + amount + script_type="PAYTOADDRESS"
 * Change output:   address_n + amount + script_type="PAYTOWITNESS"
 * Pro multisig change: multisig objekt s pubkeys.
 */
@Serializable
data class TrezorConnectOutput(
    val address: String? = null,
    val address_n: List<Long>? = null,
    val amount: String,
    val script_type: String,
    val multisig: TrezorConnectMultisig? = null
)

/**
 * Reference transaction — Trezor firmware potřebuje celé předchozí transakce
 * pro ověření částek vstupů. Stačí poskytnout raw hex přes tx_hex pole.
 */
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
    val version: Int = 2,       // tx version (2 = SegWit/RBF)
    val locktime: Int = 0
)

/**
 * Request pro broadcast raw signed transaction (z Trezor Connect serializedTx).
 */
@Serializable
data class BroadcastRawTxRequest(
    val txHex: String
)

/**
 * Request pro pridani Trezor Connect podpisu k multisig PSBT.
 * signatures = DER-encoded hex podpisy (jeden per input), z Trezor Connect response.
 * cosignerIndex = index cosignera ktery podepsal.
 */
@Serializable
data class AddTrezorSignaturesRequest(
    val signatures: List<String>,
    val cosignerIndex: Int = 0,
    val fingerprint: String,
    val serializedTx: String? = null,
    val signerAccountIndex: Int? = null
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
