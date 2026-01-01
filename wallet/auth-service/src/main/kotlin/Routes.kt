package cz.majny.wallet.authservice

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import cz.majny.wallet.authservice.RsaKeyMaterial
import cz.majny.wallet.authservice.RsaKeys
import cz.majny.wallet.authservice.JwtIssuer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
data class RefreshTokenResponse(val accessToken: String)

private val refreshIndex = ConcurrentHashMap<String, Pair<String, String?>>()

fun Application.configureAuthRoutes(jwt: JwtIssuer, keys: RsaKeyMaterial) {
    routing {
        route("/auth") {

            get("/.well-known/jwks.json") {
                val jwks = RsaKeys.toJwksJson(keys.keyId, keys.publicKey)
                call.respondText(jwks, ContentType.Application.Json)
            }

            post("/trezor/login") {
                val req = call.receive<TrezorLoginRequest>()

                // MVP deviceId (později DB/registry)
                val deviceId = "dev-${req.fingerprint}"

                val access = jwt.issueAccessToken(deviceId, req.fingerprint)
                val refresh = UUID.randomUUID().toString()

                refreshIndex[refresh] = deviceId to req.fingerprint

                call.respond(
                    TrezorLoginResponse(
                        accessToken = access,
                        refreshToken = refresh,
                        user = UserSummarySerializable(
                            id = "user-1",
                            displayName = "User",
                            trezorFingerprint = req.fingerprint,
                            wallets = listOf(
                                WalletSummarySerializable(
                                    id = "wallet-1",
                                    label = "My First Wallet",
                                    type = "SINGLE_SIG",
                                    balanceSats = 123_456
                                )
                            )
                        )
                    )
                )
            }

            post("/token/refresh") {
                val req = call.receive<RefreshTokenRequest>()
                val entry = refreshIndex[req.refreshToken]

                if (entry == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_refresh"))
                    return@post
                }

                val (deviceId, fp) = entry
                val access = jwt.issueAccessToken(deviceId, fp)
                call.respond(RefreshTokenResponse(accessToken = access))
            }
        }
    }
}
