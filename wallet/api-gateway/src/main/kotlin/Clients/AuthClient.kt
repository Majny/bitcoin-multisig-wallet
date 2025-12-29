package cz.majny.wallet.gateway.clients

import cz.majny.wallet.gateway.config.AppConfig
import cz.majny.wallet.gateway.dto.*
import cz.majny.wallet.gateway.plugins.UpstreamException
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.client.statement.*


interface AuthClient {
    suspend fun trezorLogin(req: TrezorLoginRequest): TrezorLoginResponse
    suspend fun refresh(req: RefreshTokenRequest): RefreshTokenResponse
}

class AuthClientImpl(private val cfg: AppConfig) : AuthClient {
    private lateinit var client: HttpClient

    // zavoláme si to přes Application.httpClient v konstrukci později (viz Routes)
    fun attach(http: HttpClient) { client = http }

    override suspend fun trezorLogin(req: TrezorLoginRequest): TrezorLoginResponse {
        val resp = client.post("${cfg.authBaseUrl}/auth/trezor/login") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }
        if (!resp.status.isSuccess()) throw UpstreamException("auth", resp.status, resp.bodyAsText())
        return resp.body()
    }

    override suspend fun refresh(req: RefreshTokenRequest): RefreshTokenResponse {
        val resp = client.post("${cfg.authBaseUrl}/auth/token/refresh") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }
        if (!resp.status.isSuccess()) throw UpstreamException("auth", resp.status, resp.bodyAsText())
        return resp.body()
    }
}
