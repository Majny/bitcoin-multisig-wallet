package com.example.bitcoinwallet.core.api

import android.util.Log
import com.example.bitcoinwallet.core.session.SessionPersistence
import com.example.bitcoinwallet.core.session.SessionStore
import com.example.bitcoinwallet.core.signer.UserSession
import com.example.bitcoinwallet.core.signer.UserSummary
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * Thrown by validateResponse when a 401 could not be recovered via the
 * refresh-token flow. SessionStore.signalSessionExpired has already been
 * called by the time this propagates, so the UI just needs to bail out.
 */
class SessionExpiredException(message: String) : Exception(message)

/*
 * Thrown when a 401 was met with a successful refresh — the new access
 * token is already in SessionStore, the caller just needs to retry. ViewModels
 * typically re-trigger the action on the next user input.
 */
class SessionRefreshedException(message: String = "Session refreshed — please retry") : Exception(message)

/*
 * Single HTTP-level entry point for the app. Every call hits the gateway,
 * which fans out to the relevant microservice. Most methods are thin
 * wrappers; the interesting bits live in the companion's defaultClient
 * (401 handling + token refresh single-flight) and broadcastRawTx (which
 * tolerates a non-JSON success body from upstream).
 */
class WalletApiClient(
    private val baseUrl: String,
    private val client: HttpClient = defaultClient()
) {

    // ============ Wallet Management Endpoints ============

    /* GET /wallets — every wallet attached to the authenticated device. */
    suspend fun listWallets(accessToken: String): List<MultisigWalletSummaryDto> {
        return client.get("$baseUrl/wallets") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }

    /* POST /wallets/import — import via output descriptor. */
    suspend fun importWallet(accessToken: String, request: ImportWalletRequestDto): ImportWalletResponseDto {
        return client.post("$baseUrl/wallets/import") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    // ============ Explorer Endpoints (wallet-level) ============

    /* GET /explorer/wallet/{walletId}/balance — aggregated across all addresses. */
    suspend fun getWalletBalance(walletId: String, accessToken: String): WalletBalanceDto {
        return client.get("$baseUrl/explorer/wallet/$walletId/balance") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }

    /* GET /explorer/wallet/{walletId}/transactions — history with SENT/RECEIVED
     * classification done backend-side so the UI doesn't need to diff vins/vouts. */
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

    /* GET /explorer/wallet/{walletId}/utxos — UTXO list enriched with address
     * metadata for coin control. */
    suspend fun getWalletUtxos(walletId: String, accessToken: String): WalletUtxosDto {
        return client.get("$baseUrl/explorer/wallet/$walletId/utxos") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }

    /* GET /explorer/wallet/{walletId}/receive-address — first unused address. */
    suspend fun getReceiveAddress(walletId: String, accessToken: String): ReceiveAddressDto {
        return client.get("$baseUrl/explorer/wallet/$walletId/receive-address") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }

    /* GET /psbt/verify-address — Trezor Connect getAddress params for on-device
     * address verification (multisig needs the full pubkey set + path). */
    suspend fun getVerifyAddressParams(
        walletId: String,
        index: Int,
        cosignerIndex: Int,
        accessToken: String,
        signerAccountIndex: Int? = null
    ): VerifyAddressDto {
        return client.get("$baseUrl/psbt/verify-address") {
            header("Authorization", "Bearer $accessToken")
            parameter("walletId", walletId)
            parameter("index", index)
            parameter("cosignerIndex", cosignerIndex)
            signerAccountIndex?.let { parameter("signerAccountIndex", it) }
        }.body()
    }

    /* GET /explorer/tx/{txid} — single transaction with inputs/outputs. */
    suspend fun getTransactionDetail(txid: String, accessToken: String, walletId: String? = null): TransactionDetailDto {
        return client.get("$baseUrl/explorer/tx/$txid") {
            header("Authorization", "Bearer $accessToken")
            walletId?.let { parameter("walletId", it) }
        }.body()
    }

    /* GET /explorer/fees — current sat/vB recommendations. */
    suspend fun getFeeEstimates(accessToken: String): FeeEstimatesDto {
        return client.get("$baseUrl/explorer/fees") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }

    // ============ PSBT Endpoints ============

    /* POST /psbt — build a new PSBT (UTXO selection + change). */
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

    /* GET /psbt/{id} — full PSBT detail. */
    suspend fun getPsbtDetail(psbtId: String, accessToken: String): PsbtDetailDto {
        return client.get("$baseUrl/psbt/$psbtId") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }

    /* GET /psbt/wallet/{walletId} — every PSBT (any state) for the wallet. */
    suspend fun listPsbtsForWallet(walletId: String, accessToken: String): PsbtListResponseDto {
        return client.get("$baseUrl/psbt/wallet/$walletId") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }

    /* DELETE /psbt/{id} — cancels a pending or signed PSBT and releases its
     * reserved UTXOs so they can be used in a new transaction. The backend
     * rejects deletion of already-broadcast PSBTs (audit history). */
    suspend fun deletePsbt(psbtId: String, accessToken: String) {
        val response = client.delete("$baseUrl/psbt/$psbtId") {
            header("Authorization", "Bearer $accessToken")
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            throw RuntimeException("Failed to cancel PSBT (${response.status.value}): $body")
        }
    }

    /* POST /psbt/{id}/sign-trezor — submit Trezor Connect signatures (one per
     * input) plus the optional serializedTx for the singlesig fast-path. */
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

    /* POST /psbt/{id}/broadcast-raw — push the serializedTx Trezor returned.
     * Bypasses the addSignature/finalize round-trip used by multisig. */
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
            // Some upstreams return the txid as plain text on success — treat
            // an unparseable 2xx body as success rather than failing the user.
            BroadcastResponseDto(
                psbtId = psbtId,
                txid = rawBody.trim(),
                success = true
            )
        }
    }

    /* GET /psbt/{id}/signers — per-cosigner signing status for the PSBT. */
    suspend fun getSignerStatus(psbtId: String, accessToken: String): SignerStatusResponseDto {
        return client.get("$baseUrl/psbt/$psbtId/signers") {
            header("Authorization", "Bearer $accessToken")
        }.body()
    }

    /* PUT /wallets/{walletId}/cosigners/{idx}/label — rename a cosigner row
     * (per-device label, not the wallet-wide nickname). */
    suspend fun updateCosignerLabel(walletId: String, cosignerIdx: Int, label: String, accessToken: String) {
        client.put("$baseUrl/wallets/$walletId/cosigners/$cosignerIdx/label") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(mapOf("label" to label))
        }
    }

    // ============ Price Endpoints ============

    /* GET /price — current BTC prices in CZK/USD/EUR (or any subset). */
    suspend fun getBitcoinPrices(accessToken: String, currencies: String = "czk,usd,eur"): BitcoinPricesDto {
        return client.get("$baseUrl/price") {
            header("Authorization", "Bearer $accessToken")
            parameter("currencies", currencies)
        }.body()
    }

    /* GET /price/convert — sats → fiat (server-side using the cached price). */
    suspend fun convertSatsToFiat(accessToken: String, sats: Long, currency: String = "czk"): ConversionResultDto {
        return client.get("$baseUrl/price/convert") {
            header("Authorization", "Bearer $accessToken")
            parameter("sats", sats)
            parameter("currency", currency)
        }.body()
    }

    companion object {
        // Single-flight guard so parallel 401s don't fire N concurrent refreshes.
        private val refreshMutex = Mutex()

        /*
         * Swap the in-memory refresh token for a fresh access token. Returns
         * true on success — the new token is already in SessionStore and
         * SessionPersistence by the time this returns. False means the
         * caller should propagate the original 401.
         */
        private suspend fun tryRefreshTokens(): Boolean {
            val observedRefresh = SessionStore.refreshToken ?: return false
            val currentSession = SessionStore.session ?: return false
            return refreshMutex.withLock {
                // If a sibling request entered the mutex first and rotated the token,
                // our captured value is now stale (auth-service invalidates old tokens
                // on rotate). Treat that as success — the caller just needs to retry.
                val liveRefresh = SessionStore.refreshToken ?: return@withLock false
                if (liveRefresh != observedRefresh) {
                    Log.d("WalletApiClient", "Refresh token already rotated by sibling request")
                    return@withLock true
                }
                val plainClient = HttpClient(Android) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true; isLenient = true })
                    }
                }
                try {
                    val baseUrl = com.example.bitcoinwallet.core.api.ApiConfig.API_GATEWAY_BASE_URL
                    val resp: TokenRefreshRespDto = plainClient.post("$baseUrl/auth/token/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(TokenRefreshReqDto(refreshToken = liveRefresh))
                    }.body()
                    // Write through to SessionStore (triggers persistSession).
                    SessionStore.refreshToken = resp.refreshToken ?: liveRefresh
                    SessionStore.session = UserSession(
                        accessToken = resp.accessToken,
                        user = currentSession.user
                    )
                    SessionPersistence.updateTokens(resp.accessToken, resp.refreshToken)
                    Log.d("WalletApiClient", "Access token refreshed")
                    true
                } catch (e: CancellationException) {
                    // Honor structured concurrency — never swallow cancellation,
                    // otherwise a crash can follow: an exception thrown later in
                    // validateResponse would fire inside an already-cancelled
                    // coroutine and bypass the caller's try/catch.
                    throw e
                } catch (e: Exception) {
                    Log.w("WalletApiClient", "Token refresh failed", e)
                    false
                } finally {
                    // Always release the one-shot client — body() throwing on a
                    // network blip used to leak it, slowly piling up Android
                    // dispatcher threads across long sessions.
                    plainClient.close()
                }
            }
        }

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
                            if (response.status.value == 401) {
                                // Try to refresh the access token before giving up.
                                val refreshed = tryRefreshTokens()
                                if (refreshed) {
                                    throw SessionRefreshedException()
                                }
                                // Signal the nav-host to route the user to the
                                // connect screen (mirrors WRONG_DEVICE). This
                                // also clears auth and sets the logout banner.
                                SessionStore.signalSessionExpired()
                                throw SessionExpiredException("Session expired. Please reconnect your Trezor.")
                            }
                            // Surface the backend's "error" field if present so screens can
                            // show the actual reason ("Account #1 is not a cosigner...")
                            // instead of a generic "Request failed (NNN)".
                            val backendError = try {
                                Json.parseToJsonElement(body).jsonObject["error"]
                                    ?.jsonPrimitive
                                    ?.content
                                    ?.takeIf { it.isNotBlank() }
                            } catch (_: Exception) { null }
                            throw Exception(
                                backendError
                                    ?: "Request failed (${response.status.value}). Please try again."
                            )
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
data class VerifyAddressDto(
    val path: List<Long>,
    val coin: String,
    val scriptType: String,
    val showOnTrezor: Boolean = true,
    val multisig: TrezorConnectMultisigDto? = null
)

@Serializable
data class ReceiveAddressDto(
    val walletId: String,
    val address: String,
    val index: Int,
    val isNew: Boolean = true
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

@Serializable
data class TokenRefreshReqDto(val refreshToken: String)

@Serializable
data class TokenRefreshRespDto(
    val accessToken: String,
    val refreshToken: String? = null
)
