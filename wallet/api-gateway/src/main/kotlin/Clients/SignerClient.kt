package cz.majny.wallet.gateway.clients

import cz.majny.wallet.gateway.config.AppConfig
import cz.majny.wallet.gateway.dto.*
import cz.majny.wallet.gateway.plugins.UpstreamException
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.client.statement.*


interface SignerClient {
    suspend fun signPsbt(req: SignPsbtRequest): SignPsbtResponse
}

class SignerClientImpl(private val cfg: AppConfig) : SignerClient {
    private lateinit var client: HttpClient
    fun attach(http: HttpClient) { client = http }

    override suspend fun signPsbt(req: SignPsbtRequest): SignPsbtResponse {
        val resp = client.post("${cfg.signerBaseUrl}/hwi/signpsbt") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }
        if (!resp.status.isSuccess()) throw UpstreamException("signer", resp.status, resp.bodyAsText())
        return resp.body()
    }
}
