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

/*
 * Backend client used during the Trezor-login flow. Trades an xpub set for a
 * JWT session (loginWithTrezor) and lists the wallets the resulting JWT
 * grants access to.
 *
 * Distinct from WalletApiClient: that one is the post-login HTTP surface,
 * this one only deals with the auth bootstrap.
 */
class MobileSigner(
    private val backendBaseUrl: String,
    private val client: HttpClient = defaultClient()
) {

    /*
     * Trade Trezor xpubs for a session. The backend scans every supplied
     * account for activity and auto-creates wallet rows for active ones.
     * The refresh token returned here must be stashed into
     * SessionStore.refreshToken - it is intentionally not part of UserSession.
     */
    suspend fun loginWithTrezor(identities: List<TrezorDeviceIdentity>): LoginResult {
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

        val session = UserSession(
            accessToken = loginResp.accessToken,
            user = UserSummary(
                id = loginResp.user.id,
                displayName = loginResp.user.displayName,
                trezorFingerprint = loginResp.user.trezorFingerprint,
                wallets = wallets
            )
        )
        return LoginResult(session = session, refreshToken = loginResp.refreshToken)
    }

    /*
     * GET /wallets - list every wallet visible to the JWT. Maps the registry
     * DTO into the UI-shaped WalletSummary so the rest of the app doesn't
     * have to deal with the raw transport types.
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

/* MODELS */

/* loginWithTrezor result - session plus the one-time refresh token. */
data class LoginResult(
    val session: UserSession,
    val refreshToken: String?
)

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
data class AccountToScan(
    val xpub: String,
    val derivationPath: String
)
