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

/* auth-service HTTP surface. Everything lives under /auth. The /health
 * probe is registered separately in Main.kt's routing block so the same
 * pattern holds across all services. No authentication plugin here:
 * clients talk to auth-service only through the gateway, which fronts
 * the public routes. */
fun Application.configureAuthRoutes(
    jwt: JwtIssuer,
    keys: RsaKeyMaterial,
    refreshStore: RefreshStore,
    deviceRepo: DeviceRepository
) {
    routing {
        route("/auth") {

            /* GET /auth/.well-known/jwks.json — public key published for the
             * gateway's JWKS verifier so it can check RS256 signatures without
             * sharing a secret. */
            get("/.well-known/jwks.json") {
                val jwks = RsaKeys.toJwksJson(keys.keyId, keys.publicKey)
                call.respondText(jwks, ContentType.Application.Json)
            }

            /* POST /auth/trezor/login — creates/updates a device record and
             * issues an initial access + refresh token pair. device_id is
             * derived deterministically from the Trezor fingerprint so
             * repeated logins from the same device resolve to the same ID. */
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

                call.respond(
                    TrezorLoginResponse(
                        accessToken = access,
                        refreshToken = refresh,
                        user = UserSummarySerializable(
                            id = "user-$deviceId",
                            displayName = "User",
                            trezorFingerprint = req.fingerprint,
                            wallets = emptyList()
                        )
                    )
                )
            }

            /* POST /auth/device — upserts extended device metadata (model, label).
             * The login flow calls this right after issuing the token so that
             * the Trezor Suite model/label make it into the devices row. */
            post("/device") {
                val req = call.receive<UpsertDeviceRequest>()
                val out = deviceRepo.upsertDevice(req)
                call.respond(out)
            }

            /* GET /auth/device/{id} — single-row device lookup. */
            get("/device/{id}") {
                val id = call.parameters["id"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "missing device id")
                )
                val device = deviceRepo.getDevice(id)
                if (device != null) {
                    call.respond(device)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "device not found"))
                }
            }

            /* POST /auth/token/refresh — single-use refresh token rotation.
             * Delegates atomicity to RefreshStore.rotate, which makes sure
             * concurrent rotations of the same token don't hand out two
             * valid replacements. */
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

/* Builds a stable device_id from a Trezor fingerprint. Uses Java's
 * UUID.nameUUIDFromBytes (type 3, MD5-based) with a "trezor:" prefix so
 * the same fingerprint maps to the same UUID across logins and the prefix
 * leaves room for different hardware categories in the future. */
private fun deterministicDeviceId(fingerprint: String): String {
    val name = "trezor:$fingerprint"
    return UUID.nameUUIDFromBytes(name.toByteArray(StandardCharsets.UTF_8)).toString()
}
