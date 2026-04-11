package com.example.bitcoinwallet.core.api

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * API client for wallet data.
 * Communicates with API Gateway which routes to explorer-service, blockchain-service, and price-service.
 */
class WalletApiClient(
    private val baseUrl: String,
    private val client: HttpClient = defaultClient()
) {
    
    // ============ Wallet Management Endpoints ============

    /**
     * GET /api/v1/wallets
     * List all wallets for the authenticated device.
     */
    suspend fun listWallets(accessToken: String): List<MultisigWalletSummaryDto> {
        return client.get("$baseUrl/wallets") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }

    /**
     * POST /api/v1/wallets/import
     * Import a wallet from an output descriptor.
     */
    suspend fun importWallet(accessToken: String, request: ImportWalletRequestDto): ImportWalletResponseDto {
        return client.post("$baseUrl/wallets/import") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    // ============ Explorer Endpoints (wallet-level) ============
    
    /**
     * GET /api/v1/explorer/wallet/{walletId}/balance
     * Aggregated balance across all wallet addresses.
     */
    suspend fun getWalletBalance(walletId: String, accessToken: String): WalletBalanceDto {
        return client.get("$baseUrl/explorer/wallet/$walletId/balance") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }
    
    /**
     * GET /api/v1/explorer/wallet/{walletId}/transactions?limit=50&offset=0
     * Transaction history with SENT/RECEIVED classification.
     */
    suspend fun getWalletTransactions(
        walletId: String,
        accessToken: String,
        limit: Int = 50,
        offset: Int = 0
    ): WalletTransactionsDto {
        return client.get("$baseUrl/explorer/wallet/$walletId/transactions") {
            header("Authorization", "Bearer $accessToken")
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()
    }
    
    /**
     * GET /api/v1/explorer/wallet/{walletId}/utxos
     * All UTXOs enriched with address info for coin control.
     */
    suspend fun getWalletUtxos(walletId: String, accessToken: String): WalletUtxosDto {
        return client.get("$baseUrl/explorer/wallet/$walletId/utxos") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }
    
    /**
     * GET /api/v1/explorer/wallet/{walletId}/receive-address
     * First unused receive address.
     */
    suspend fun getReceiveAddress(walletId: String, accessToken: String): ReceiveAddressDto {
        return client.get("$baseUrl/explorer/wallet/$walletId/receive-address") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }
    
    /**
     * GET /api/v1/explorer/tx/{txid}
     * Transaction detail with inputs/outputs.
     */
    suspend fun getTransactionDetail(txid: String, accessToken: String, walletId: String? = null): TransactionDetailDto {
        return client.get("$baseUrl/explorer/tx/$txid") {
            header("Authorization", "Bearer $accessToken")
            walletId?.let { parameter("walletId", it) }
        }.body()
    }
    
    /**
     * GET /api/v1/explorer/fees
     * Recommended fee rates.
     */
    suspend fun getFeeEstimates(accessToken: String): FeeEstimatesDto {
        return client.get("$baseUrl/explorer/fees") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }

    // ============ PSBT Endpoints ============

    /**
     * POST /api/v1/psbt
     * Create a new PSBT transaction.
     */
    suspend fun createPsbt(
        accessToken: String,
        walletId: String,
        destinationAddress: String,
        amountSats: Long,
        feeRate: Double,
        utxos: List<UtxoSelectionDto>? = null,
        rbf: Boolean = true,
        label: String? = null,
        signerAccountIndex: Int? = null
    ): CreatePsbtResponseDto {
        return client.post("$baseUrl/psbt") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(
                CreatePsbtRequestDto(
                    walletId = walletId,
                    outputs = listOf(PsbtTxOutputDto(address = destinationAddress, amountSats = amountSats)),
                    feeRate = feeRate,
                    utxos = utxos,
                    rbf = rbf,
                    label = label,
                    signerAccountIndex = signerAccountIndex
                )
            )
        }.body()
    }

    /**
     * GET /api/v1/psbt/{id}
     * Get PSBT detail.
     */
    suspend fun getPsbtDetail(psbtId: String, accessToken: String): PsbtDetailDto {
        return client.get("$baseUrl/psbt/$psbtId") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }

    /**
     * GET /api/v1/psbt/wallet/{walletId}
     * List all PSBTs for a wallet.
     */
    suspend fun listPsbtsForWallet(walletId: String, accessToken: String): PsbtListResponseDto {
        return client.get("$baseUrl/psbt/wallet/$walletId") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }

    /**
     * POST /api/v1/psbt/{id}/sign-trezor
     * Submit Trezor Connect signatures for multisig PSBT.
     */
    suspend fun signTrezor(
        psbtId: String,
        accessToken: String,
        signatures: List<String>,
        cosignerIndex: Int,
        fingerprint: String,
        serializedTx: String? = null,
        signerAccountIndex: Int? = null
    ): PsbtDetailDto {
        return client.post("$baseUrl/psbt/$psbtId/sign-trezor") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(
                AddTrezorSignaturesRequestDto(
                    signatures = signatures,
                    cosignerIndex = cosignerIndex,
                    fingerprint = fingerprint,
                    serializedTx = serializedTx,
                    signerAccountIndex = signerAccountIndex
                )
            )
        }.body()
    }

    /**
     * POST /api/v1/psbt/{id}/broadcast-raw
     * Broadcast a raw signed transaction hex (from Trezor Connect serializedTx).
     * Skips the addSignature/finalize flow.
     */
    suspend fun broadcastRawTx(
        psbtId: String,
        accessToken: String,
        txHex: String
    ): BroadcastResponseDto {
        val response = client.post("$baseUrl/psbt/$psbtId/broadcast-raw") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(BroadcastRawTxRequestDto(txHex = txHex))
        }

        val rawBody = response.bodyAsText()
        Log.d("WalletApiClient", "broadcastRawTx response: status=${response.status.value}, body=$rawBody")

        if (!response.status.isSuccess()) {
            throw Exception("Broadcast failed (${response.status.value}): $rawBody")
        }

        return try {
            Json { ignoreUnknownKeys = true }.decodeFromString<BroadcastResponseDto>(rawBody)
        } catch (e: Exception) {
            Log.e("WalletApiClient", "Failed to parse broadcast response, raw: $rawBody", e)
            // Broadcast likely succeeded on the backend — construct response from known data
            BroadcastResponseDto(
                psbtId = psbtId,
                txid = rawBody.trim(),  // mempool.space sometimes returns just the txid as plain text
                success = true
            )
        }
    }

    /**
     * GET /api/v1/psbt/{id}/signers
     * Get cosigner signing status for a PSBT.
     */
    suspend fun getSignerStatus(psbtId: String, accessToken: String): SignerStatusResponseDto {
        return client.get("$baseUrl/psbt/$psbtId/signers") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }

    /**
     * PUT /api/v1/wallets/{walletId}/cosigners/{idx}/label
     * Updates the display label for a cosigner.
     */
    suspend fun updateCosignerLabel(walletId: String, cosignerIdx: Int, label: String, accessToken: String) {
        client.put("$baseUrl/wallets/$walletId/cosigners/$cosignerIdx/label") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(mapOf("label" to label))
        }
    }

    // ============ Price Endpoints ============
    
    /**
     * GET /api/v1/price
     * Get current Bitcoin prices in various currencies.
     */
    suspend fun getBitcoinPrices(accessToken: String, currencies: String = "czk,usd,eur"): BitcoinPricesDto {
        return client.get("$baseUrl/price") {
            header("Authorization", "Bearer $accessToken")
            parameter("currencies", currencies)
        }.body()
    }
    
    /**
     * GET /api/v1/price/convert
     * Convert satoshis to fiat value.
     */
    suspend fun convertSatsToFiat(accessToken: String, sats: Long, currency: String = "czk"): ConversionResultDto {
        return client.get("$baseUrl/price/convert") {
            header("Authorization", "Bearer $accessToken")
            parameter("sats", sats)
            parameter("currency", currency)
        }.body()
    }
    
    companion object {
        private fun defaultClient(): HttpClient =
            HttpClient(Android) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            prettyPrint = false
                            isLenient = true
                            explicitNulls = false
                        }
                    )
                }
                // Throw on non-2xx responses so ViewModels get a proper Exception
                // instead of crashing on serialization of error bodies
                HttpResponseValidator {
                    validateResponse { response ->
                        if (!response.status.isSuccess()) {
                            val body = response.bodyAsText()
                            Log.e("WalletApiClient", "HTTP ${response.status.value}: $body")
                            throw Exception("Request failed (${response.status.value}). Please try again.")
                        }
                    }
                }
            }
    }
}

// ============ Explorer DTOs ============

@Serializable
data class WalletBalanceDto(
    val walletId: String,
    val confirmedSats: Long,
    val unconfirmedSats: Long,
    val totalSats: Long,
    val utxoCount: Int,
    val addressCount: Int
)

@Serializable
data class WalletTransactionsDto(
    val walletId: String,
    val transactions: List<WalletTransactionDto>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

@Serializable
data class WalletTransactionDto(
    val txid: String,
    val type: String,           // "SENT" or "RECEIVED"
    val amountSats: Long,
    val fee: Long = 0,
    val confirmed: Boolean,
    val blockHeight: Int? = null,
    val blockTime: Long? = null,
    val confirmations: Int = 0,
    val inputCount: Int = 0,
    val outputCount: Int = 0,
    val size: Int = 0,
    val weight: Int = 0
)

@Serializable
data class WalletUtxosDto(
    val walletId: String,
    val utxos: List<WalletUtxoDto>,
    val totalSats: Long,
    val count: Int
)

@Serializable
data class WalletUtxoDto(
    val txid: String,
    val vout: Int,
    val valueSats: Long,
    val address: String,
    val addressIndex: Int,
    val addressType: String,
    val confirmed: Boolean,
    val blockHeight: Int? = null,
    val blockTime: Long? = null
)

@Serializable
data class ReceiveAddressDto(
    val walletId: String,
    val address: String,
    val index: Int,
    val isNew: Boolean = true,
    val needsDerivation: Boolean = false
)

@Serializable
data class TransactionDetailDto(
    val txid: String,
    val version: Int = 2,
    val locktime: Int = 0,
    val size: Int = 0,
    val weight: Int = 0,
    val fee: Long = 0,
    val confirmed: Boolean = false,
    val blockHeight: Int? = null,
    val blockTime: Long? = null,
    val inputs: List<TxInputDto> = emptyList(),
    val outputs: List<TxOutputDto> = emptyList()
)

@Serializable
data class TxInputDto(
    val txid: String,
    val vout: Int,
    val address: String,
    val valueSats: Long,
    val isMine: Boolean = false
)

@Serializable
data class TxOutputDto(
    val index: Int,
    val address: String,
    val valueSats: Long,
    val isMine: Boolean = false
)

// ============ Price DTOs ============

@Serializable
data class FeeEstimatesDto(
    val fastestFee: Int,
    val halfHourFee: Int,
    val hourFee: Int,
    val economyFee: Int,
    val minimumFee: Int
)

@Serializable
data class BitcoinPricesDto(
    val czk: Double? = null,
    val usd: Double? = null,
    val eur: Double? = null,
    val czk24hChange: Double? = null,
    val usd24hChange: Double? = null,
    val eur24hChange: Double? = null,
    val lastUpdatedAt: Long? = null,
    val cachedAt: Long? = null
)

@Serializable
data class ConversionResultDto(
    val satoshis: Long,
    val btc: Double,
    val fiatValue: Double,
    val fiatCurrency: String
)

// ============ PSBT DTOs ============

@Serializable
data class PsbtTxOutputDto(
    val address: String,
    val amountSats: Long
)

@Serializable
data class UtxoSelectionDto(
    val txid: String,
    val vout: Int,
    val address: String? = null
)

@Serializable
data class CreatePsbtRequestDto(
    val walletId: String,
    val outputs: List<PsbtTxOutputDto>,
    val feeRate: Double,
    val utxos: List<UtxoSelectionDto>? = null,
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
data class TrezorConnectMultisigPubkeyDto(
    val node: HDNodeDto,
    val address_n: List<Long>
)

@Serializable
data class TrezorConnectMultisigDto(
    val pubkeys: List<TrezorConnectMultisigPubkeyDto>,
    val m: Int,
    val signatures: List<String> = emptyList()
)

@Serializable
data class TrezorConnectInputDto(
    val address_n: List<Long>,
    val prev_hash: String,
    val prev_index: Int,
    val amount: String,
    val script_type: String = "SPENDWITNESS",
    val sequence: Long = 0xFFFFFFFDL,
    val multisig: TrezorConnectMultisigDto? = null
)

@Serializable
data class TrezorConnectOutputDto(
    val address: String? = null,
    val address_n: List<Long>? = null,
    val amount: String,
    val script_type: String,
    val multisig: TrezorConnectMultisigDto? = null
)

@Serializable
data class TrezorConnectRefTxDto(
    val hash: String,
    val version: Int,
    val lock_time: Int,
    val inputs: List<TrezorConnectRefTxInputDto>,
    val bin_outputs: List<TrezorConnectRefTxBinOutputDto>
)

@Serializable
data class TrezorConnectRefTxInputDto(
    val prev_hash: String,
    val prev_index: Long,
    val script_sig: String,
    val sequence: Long
)

@Serializable
data class TrezorConnectRefTxBinOutputDto(
    val amount: Long,
    val script_pubkey: String
)

@Serializable
data class TrezorConnectParamsDto(
    val coin: String,
    val inputs: List<TrezorConnectInputDto>,
    val outputs: List<TrezorConnectOutputDto>,
    val refTxs: List<TrezorConnectRefTxDto>? = null,
    val version: Int = 2,
    val locktime: Int = 0
)

@Serializable
data class BroadcastRawTxRequestDto(
    val txHex: String
)

@Serializable
data class CreatePsbtResponseDto(
    val id: String,
    val psbtBase64: String,
    val estimatedFee: Long,
    val estimatedVsize: Int,
    val trezorConnectParams: TrezorConnectParamsDto? = null,
    val signerCosignerIndex: Int = 0
)

@Serializable
data class PsbtDetailDto(
    val id: String,
    val walletId: String,
    val psbtBase64: String,
    val status: String,
    val requiredSigs: Int,
    val currentSigs: Int,
    val totalOutputSats: Long = 0,
    val estimatedFeeSats: Long = 0,
    val signatures: List<PsbtSignatureDto> = emptyList(),
    val label: String? = null,
    val txid: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val trezorConnectParams: TrezorConnectParamsDto? = null,
    val serializedTx: String? = null
)

@Serializable
data class PsbtListResponseDto(
    val psbts: List<PsbtDetailDto>
)

@Serializable
data class PsbtSignatureDto(
    val fingerprint: String,
    val deviceId: String,
    val cosignerIndex: Int = 0,
    val signedAt: String
)

@Serializable
data class AddTrezorSignaturesRequestDto(
    val signatures: List<String>,
    val cosignerIndex: Int,
    val fingerprint: String,
    val serializedTx: String? = null,
    val signerAccountIndex: Int? = null
)

@Serializable
data class BroadcastResponseDto(
    val psbtId: String,
    val txid: String,
    val success: Boolean
)

// ============ Signer Status DTOs ============

@Serializable
data class SignerStatusResponseDto(
    val psbtId: String,
    val walletId: String,
    val status: String,
    val requiredSigs: Int,
    val currentSigs: Int,
    val signers: List<SignerDetailDto>
)

@Serializable
data class SignerDetailDto(
    val fingerprint: String,
    val cosignerIndex: Int,
    val originPath: String? = null,
    val xpub: String? = null,
    val signed: Boolean,
    val deviceId: String? = null,
    val signedAt: String? = null,
    val label: String? = null
)

// ============ Wallet Management DTOs ============

@Serializable
data class MultisigWalletSummaryDto(
    val id: String = "",
    val walletId: String = "",
    val label: String = "",
    val type: String = "",
    val balanceSats: Long = 0,
    val network: String = "mainnet",
    val scriptType: String = "",
    val m: Int? = null,
    val n: Int? = null,
    val accountIndex: Int? = null,
    val cosignerAccountIndex: Int? = null
)

@Serializable
data class ImportWalletRequestDto(
    val descriptor: String,
    val network: String = "mainnet",
    val label: String? = null,
    val birthHeight: Int? = null,
    val accountIndex: Int? = null
)

@Serializable
data class ImportWalletResponseDto(
    val success: Boolean,
    val walletId: String? = null,
    val isNew: Boolean = false,
    val error: String? = null
)
