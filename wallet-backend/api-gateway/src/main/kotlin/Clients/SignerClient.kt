package cz.majny.wallet.gateway.clients

import cz.majny.wallet.gateway.config.AppConfig
import cz.majny.wallet.gateway.dto.SignPsbtRequest
import cz.majny.wallet.gateway.dto.SignPsbtResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

/* HTTP client facade for a dedicated signer service (HWI-style). Currently
 * unused at runtime because signing is delegated to Trezor Suite Mobile via
 * deeplinks; kept for a future direct-USB signing path. */
interface SignerClient {
    suspend fun signPsbt(req: SignPsbtRequest): SignPsbtResponse
}

class SignerClientImpl(private val cfg: AppConfig) : SignerClient {
    private lateinit var client: HttpClient
    fun attach(http: HttpClient) { client = http }

    /* POST /hwi/signpsbt — forwards a PSBT to the signer service. */
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
