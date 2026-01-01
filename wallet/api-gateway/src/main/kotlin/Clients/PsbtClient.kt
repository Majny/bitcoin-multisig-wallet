package cz.majny.wallet.gateway.clients

import cz.majny.wallet.gateway.config.AppConfig
import cz.majny.wallet.gateway.dto.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

interface PsbtClient {
    suspend fun prepare(walletId: String, req: PreparePsbtBackendRequest): PreparePsbtResponse
    suspend fun submit(walletId: String, req: SubmitSignedPsbtBackendRequest): SubmitSignedPsbtBackendResponse
}

class PsbtClientImpl(private val cfg: AppConfig) : PsbtClient {
    private lateinit var client: HttpClient
    fun attach(http: HttpClient) { client = http }

    override suspend fun prepare(walletId: String, req: PreparePsbtBackendRequest): PreparePsbtResponse {
        requireAttached(this::client.isInitialized, "psbt")

        val resp = upstreamRequest("psbt") {
            client.post("${cfg.psbtBaseUrl}/wallets/$walletId/tx/prepare") {
                contentType(ContentType.Application.Json)
                setBody(req)
            }
        }.ensureSuccess("psbt")

        return resp.body()
    }

    override suspend fun submit(walletId: String, req: SubmitSignedPsbtBackendRequest): SubmitSignedPsbtBackendResponse {
        requireAttached(this::client.isInitialized, "psbt")

        val resp = upstreamRequest("psbt") {
            client.post("${cfg.psbtBaseUrl}/wallets/$walletId/tx/submit") {
                contentType(ContentType.Application.Json)
                setBody(req)
            }
        }.ensureSuccess("psbt")

        return resp.body()
    }
}
