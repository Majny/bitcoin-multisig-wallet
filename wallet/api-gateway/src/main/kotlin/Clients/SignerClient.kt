package cz.majny.wallet.gateway.clients

import cz.majny.wallet.gateway.config.AppConfig
import cz.majny.wallet.gateway.dto.SignPsbtRequest
import cz.majny.wallet.gateway.dto.SignPsbtResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

interface SignerClient {
    suspend fun signPsbt(req: SignPsbtRequest): SignPsbtResponse
}

class SignerClientImpl(private val cfg: AppConfig) : SignerClient {
    private lateinit var client: HttpClient
    fun attach(http: HttpClient) { client = http }

    override suspend fun signPsbt(req: SignPsbtRequest): SignPsbtResponse {
        requireAttached(this::client.isInitialized, "signer")

        val resp: HttpResponse = upstreamRequest("signer") {
            client.post("${cfg.signerBaseUrl}/hwi/signpsbt") {
                contentType(ContentType.Application.Json)
                setBody(req)
            }
        }.ensureSuccess("signer")

        return resp.body()
    }
}
