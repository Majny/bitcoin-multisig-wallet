package cz.majny.wallet.authservice

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.util.UUID

@Serializable
data class TrezorLoginRequest(
    val fingerprint: String,
    val xpub: String,
    val derivationPath: String,
    val deviceModel: String? = null,
    val deviceLabel: String? = null
)

@Serializable
data class WalletSummarySerializable(
    val id: String,
    val label: String,
    val type: String,
    val balanceSats: Long
)

@Serializable
data class UserSummarySerializable(
    val id: String,
    val displayName: String,
    val trezorFingerprint: String,
    val wallets: List<WalletSummarySerializable>
)

@Serializable
data class TrezorLoginResponse(
    val accessToken: String,
    val refreshToken: String,
    val user: UserSummarySerializable
)

@Serializable
data class RefreshTokenRequest(val refreshToken: String)

@Serializable
data class RefreshTokenResponse(
    val accessToken: String,
    val refreshToken: String
)

fun Application.configureAuthRoutes(
    jwt: JwtIssuer,
    keys: RsaKeyMaterial,
    refreshStore: RefreshStore
) {
    routing {
        get("/health") { call.respondText("ok") }

        route("/auth") {

            get("/.well-known/jwks.json") {
                val jwks = RsaKeys.toJwksJson(keys.keyId, keys.publicKey)
                call.respondText(jwks, ContentType.Application.Json)
            }

            post("/trezor/login") {
                val req = call.receive<TrezorLoginRequest>()

                if (req.fingerprint.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing_fingerprint"))
                    return@post
                }
                if (req.xpub.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing_xpub"))
                    return@post
                }

                val deviceId = deterministicDeviceId(req.fingerprint)

                val access = jwt.issueAccessToken(deviceId, req.fingerprint)
                val refresh = refreshStore.issue(deviceId, req.fingerprint)

                // TODO: remove wallet
                call.respond(
                    TrezorLoginResponse(
                        accessToken = access,
                        refreshToken = refresh,
                        user = UserSummarySerializable(
                            id = "user-$deviceId",
                            displayName = "User",
                            trezorFingerprint = req.fingerprint,
                            wallets = listOf(
                                WalletSummarySerializable(
                                    id = "wallet-1",
                                    label = "My First Wallet",
                                    type = "SINGLE_SIG",
                                    balanceSats = 0L
                                )
                            )
                        )
                    )
                )
            }

            post("/token/refresh") {
                val req = call.receive<RefreshTokenRequest>()

                val rotated = refreshStore.rotate(req.refreshToken)
                if (rotated == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_or_expired_refresh"))
                    return@post
                }

                val (deviceId, fp, newRefresh) = rotated
                val access = jwt.issueAccessToken(deviceId, fp)

                call.respond(
                    RefreshTokenResponse(
                        accessToken = access,
                        refreshToken = newRefresh
                    )
                )
            }
        }
    }
}

private fun deterministicDeviceId(fingerprint: String): String {
    val name = "trezor:$fingerprint"
    return UUID.nameUUIDFromBytes(name.toByteArray(StandardCharsets.UTF_8)).toString()
}
