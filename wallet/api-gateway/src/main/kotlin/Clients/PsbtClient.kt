package cz.majny.wallet.gateway.clients

import cz.majny.wallet.gateway.config.AppConfig
import cz.majny.wallet.gateway.dto.*
import cz.majny.wallet.gateway.plugins.UpstreamException
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.client.statement.*


interface PsbtClient {
    suspend fun prepare(walletId: String, req: PreparePsbtBackendRequest): PreparePsbtResponse
    suspend fun submit(walletId: String, req: SubmitSignedPsbtBackendRequest): SubmitSignedPsbtBackendResponse
}

class PsbtClientImpl(private val cfg: AppConfig) : PsbtClient {
    private lateinit var client: HttpClient
    fun attach(http: HttpClient) { client = http }

    override suspend fun prepare(walletId: String, req: PreparePsbtBackendRequest): PreparePsbtResponse {
        val resp = client.post("${cfg.psbtBaseUrl}/wallets/$walletId/tx/prepare") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }
        if (!resp.status.isSuccess()) throw UpstreamException("psbt", resp.status, resp.bodyAsText())
        return resp.body()
    }

    override suspend fun submit(walletId: String, req: SubmitSignedPsbtBackendRequest): SubmitSignedPsbtBackendResponse {
        val resp = client.post("${cfg.psbtBaseUrl}/wallets/$walletId/tx/submit") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }
        if (!resp.status.isSuccess()) throw UpstreamException("psbt", resp.status, resp.bodyAsText())
        return resp.body()
    }
}
