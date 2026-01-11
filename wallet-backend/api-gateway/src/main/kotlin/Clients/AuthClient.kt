package cz.majny.wallet.gateway.clients

import cz.majny.wallet.gateway.config.AppConfig
import cz.majny.wallet.gateway.dto.RefreshTokenRequest
import cz.majny.wallet.gateway.dto.RefreshTokenResponse
import cz.majny.wallet.gateway.dto.TrezorLoginRequest
import cz.majny.wallet.gateway.dto.TrezorLoginResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

interface AuthClient {
    suspend fun trezorLogin(req: TrezorLoginRequest): TrezorLoginResponse
    suspend fun refresh(req: RefreshTokenRequest): RefreshTokenResponse
}

class AuthClientImpl(private val cfg: AppConfig) : AuthClient {
    private lateinit var client: HttpClient
    fun attach(http: HttpClient) { client = http }

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
}
