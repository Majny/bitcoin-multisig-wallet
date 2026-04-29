package cz.majny.wallet.gateway.clients

import cz.majny.wallet.gateway.config.AppConfig
import cz.majny.wallet.gateway.dto.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

/* Thin HTTP client facade for auth-service endpoints used by gateway routes. */
interface AuthClient {
    suspend fun trezorLogin(req: TrezorLoginRequest): TrezorLoginResponse
    suspend fun refresh(req: RefreshTokenRequest): RefreshTokenResponse
    suspend fun upsertDevice(req: UpsertDeviceRequest): UpsertDeviceResponse
}

class AuthClientImpl(private val cfg: AppConfig) : AuthClient {
    private lateinit var client: HttpClient

    /* Called once by attachHttpClients after the shared HttpClient is created. */
    fun attach(http: HttpClient) { client = http }

    /* POST /auth/trezor/login - creates a device + issues access/refresh tokens. */
    override suspend fun trezorLogin(req: TrezorLoginRequest): TrezorLoginResponse {
        requireAttached(this::client.isInitialized, "auth")

        val resp = upstreamRequest("auth") {
            client.post("${cfg.authBaseUrl}/auth/trezor/login") {
                contentType(ContentType.Application.Json)
                setBody(req)
            }
        }.ensureSuccess("auth")

        return resp.body()
    }

    /* POST /auth/token/refresh - single-use rotation of a refresh token for a
     * new access/refresh pair. */
    override suspend fun refresh(req: RefreshTokenRequest): RefreshTokenResponse {
        requireAttached(this::client.isInitialized, "auth")

        val resp = upstreamRequest("auth") {
            client.post("${cfg.authBaseUrl}/auth/token/refresh") {
                contentType(ContentType.Application.Json)
                setBody(req)
            }
        }.ensureSuccess("auth")

        return resp.body()
    }

    /* POST /auth/device - upserts extended device metadata (model, label) that
     * isn't captured by the login flow. */
    override suspend fun upsertDevice(req: UpsertDeviceRequest): UpsertDeviceResponse {
        requireAttached(this::client.isInitialized, "auth")

        val resp = upstreamRequest("auth") {
            client.post("${cfg.authBaseUrl}/auth/device") {
                contentType(ContentType.Application.Json)
                setBody(req)
            }
        }.ensureSuccess("auth")

        return resp.body()
    }
}
