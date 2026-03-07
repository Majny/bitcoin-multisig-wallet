package com.example.bitcoinwallet.core.signer

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * MobileSigner = klient pro backend z pohledu Android appky.
 *
 * - login Trezorem (fingerprint/xpub)
 * - list wallets (GET /wallets)  <-- jediný "source of truth" pro network/scriptType
 * - PSBT prepare/submit
 */
class MobileSigner(
    private val backendBaseUrl: String,
    private val client: HttpClient = defaultClient()
) {

    /**
     * Login with one or more Trezor account xpubs.
     * Backend scans each account for blockchain activity and creates wallets for active ones.
     */
    suspend fun loginWithTrezor(identities: List<TrezorDeviceIdentity>): UserSession {
        val primary = identities.first()
        val loginResp: TrezorLoginResponse =
            client.post("$backendBaseUrl/auth/trezor/login") {
                contentType(ContentType.Application.Json)
                setBody(
                    TrezorLoginRequest(
                        fingerprint = primary.fingerprint,
                        xpub = primary.xpub,
                        derivationPath = primary.derivationPath,
                        deviceModel = primary.deviceModel,
                        deviceLabel = primary.deviceLabel,
                        accounts = identities.map { AccountToScan(it.xpub, it.derivationPath) }
                    )
                )
            }.body()

        val wallets = listWallets(loginResp.accessToken)

        return UserSession(
            accessToken = loginResp.accessToken,
            user = UserSummary(
                id = loginResp.user.id,
                displayName = loginResp.user.displayName,
                trezorFingerprint = loginResp.user.trezorFingerprint,
                wallets = wallets
            )
        )
    }

    /**
     * GET {backendBaseUrl}/wallets
     *
     * [
     *   { walletId, network, type, scriptType, m, n, label }
     * ]
     */
    suspend fun listWallets(accessToken: String): List<WalletSummary> {
        val resp: List<RegistryWalletSummaryDto> =
            client.get("$backendBaseUrl/wallets") {
                header("Authorization", "Bearer $accessToken")
            }.body()

        return resp.map {
            WalletSummary(
                id = it.walletId,
                label = it.label,
                type = when (it.type.uppercase()) {
                    "MULTI_SIG" -> WalletType.MULTI_SIG
                    else -> WalletType.SINGLE_SIG
                },
                network = it.network,
                scriptType = it.scriptType,
                m = it.m,
                n = it.n,
                accountIndex = it.accountIndex
            )
        }
    }


    /**
     * POST {backendBaseUrl}/wallets/{walletId}/tx/prepare
     */
    suspend fun preparePsbt(
        accessToken: String,
        request: PreparePsbtRequest
    ): PreparedPsbt {
        val response: PreparePsbtResponse =
            client.post("$backendBaseUrl/wallets/${request.walletId}/tx/prepare") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $accessToken")
                setBody(
                    PreparePsbtBackendRequest(
                        amountSats = request.amountSats,
                        destinationAddress = request.destinationAddress,
                        feeRateSatsPerVb = request.feeRateSatsPerVb,
                        selectedInputs = request.selectedInputs
                    )
                )
            }.body()

        return PreparedPsbt(
            psbtId = response.psbtId,
            psbtBase64 = response.psbtBase64,
            walletId = request.walletId
        )
    }

    /**
     * POST {backendBaseUrl}/wallets/{walletId}/tx/submit
     */
    suspend fun submitSignedPsbt(
        accessToken: String,
        request: SubmitSignedPsbtRequest
    ): SubmitSignedPsbtResult {
        val response: SubmitSignedPsbtBackendResponse =
            client.post("$backendBaseUrl/wallets/${request.walletId}/tx/submit") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $accessToken")
                setBody(
                    SubmitSignedPsbtBackendRequest(
                        psbtId = request.psbtId,
                        signedPsbtBase64 = request.signedPsbtBase64
                    )
                )
            }.body()

        return SubmitSignedPsbtResult(
            status = response.status,
            txId = response.txId
        )
    }

    /**
     * POST {backendBaseUrl}/accounts/scan
     *
     * Scans multiple derivation paths to find accounts with blockchain activity.
     * Used during account discovery to show user which accounts have funds.
     */
    suspend fun scanAccounts(
        fingerprint: String,
        accounts: List<AccountToScan>
    ): ScanAccountsResponse {
        return client.post("$backendBaseUrl/accounts/scan") {
            contentType(ContentType.Application.Json)
            setBody(
                ScanAccountsRequest(
                    fingerprint = fingerprint,
                    accounts = accounts
                )
            )
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
            }
    }
}

/* ---------- MODELS ---------- */

data class TrezorDeviceIdentity(
    val fingerprint: String,
    val xpub: String,
    val derivationPath: String,
    val deviceModel: String?,
    val deviceLabel: String?
)

data class UserSession(
    val accessToken: String,
    val user: UserSummary
)

data class UserSummary(
    val id: String,
    val displayName: String,
    val trezorFingerprint: String,
    val wallets: List<WalletSummary>
)

data class WalletSummary(
    val id: String,
    val label: String? = null,
    val type: WalletType,
    val network: String,
    val scriptType: String,
    val m: Int? = null,
    val n: Int? = null,
    val accountIndex: Int? = null
)

enum class WalletType {
    SINGLE_SIG,
    MULTI_SIG
}

data class PreparePsbtRequest(
    val walletId: String,
    val amountSats: Long,
    val destinationAddress: String,
    val feeRateSatsPerVb: Long?,
    val selectedInputs: List<CoinSelectionInput> = emptyList()
)

@Serializable
data class CoinSelectionInput(
    val txid: String,
    val vout: Int
)


@Serializable
data class RegistryWalletSummaryDto(
    val walletId: String,
    val network: String,
    val type: String,
    val scriptType: String,
    val m: Int? = null,
    val n: Int? = null,
    val label: String? = null,
    val accountIndex: Int? = null
)


data class PreparedPsbt(
    val psbtId: String,
    val psbtBase64: String,
    val walletId: String
)

data class SubmitSignedPsbtRequest(
    val walletId: String,
    val psbtId: String,
    val signedPsbtBase64: String
)

data class SubmitSignedPsbtResult(
    val status: String,
    val txId: String?
)


@Serializable
data class TrezorLoginRequest(
    val fingerprint: String,
    val xpub: String = "",
    val derivationPath: String = "",
    val deviceModel: String? = null,
    val deviceLabel: String? = null,
    val accounts: List<AccountToScan>? = null
)


@Serializable
data class TrezorLoginResponse(
    val accessToken: String,
    val refreshToken: String? = null,
    val user: UserSummaryLoginSerializable
)

@Serializable
data class UserSummaryLoginSerializable(
    val id: String,
    val displayName: String,
    val trezorFingerprint: String,
    val wallets: List<WalletLoginSerializable> = emptyList()
)

@Serializable
data class WalletLoginSerializable(
    val id: String,
    val label: String? = null,
    val type: String,
    val balanceSats: Long? = null
)


@Serializable
data class PreparePsbtBackendRequest(
    val amountSats: Long,
    val destinationAddress: String,
    val feeRateSatsPerVb: Long?,
    val selectedInputs: List<CoinSelectionInput>
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

/* ---------- ACCOUNT DISCOVERY ---------- */

@Serializable
data class AccountToScan(
    val xpub: String,
    val derivationPath: String
)

@Serializable
data class ScanAccountsRequest(
    val fingerprint: String,
    val accounts: List<AccountToScan>
)

@Serializable
data class ScanAccountsResponse(
    val fingerprint: String,
    val accounts: List<ScannedAccount>
)

@Serializable
data class ScannedAccount(
    val derivationPath: String,
    val xpub: String,
    val hasActivity: Boolean,
    val utxoCount: Int,
    val totalSats: Long,
    val scriptType: String,
    val network: String
)
