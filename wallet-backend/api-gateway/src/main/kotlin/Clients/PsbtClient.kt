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
    
    // Nové metody pro psbt-service
    suspend fun create(req: CreatePsbtRequest): CreatePsbtResponse
    suspend fun getById(id: String): PsbtDetailResponse
    suspend fun getByWallet(walletId: String, status: String? = null): PsbtListResponse
    suspend fun addSignature(id: String, req: AddSignatureRequest): PsbtDetailResponse
    suspend fun combine(id: String, req: CombinePsbtsRequest): PsbtDetailResponse
    suspend fun finalize(id: String): FinalizeResponse
    suspend fun broadcast(id: String): BroadcastResponse
    suspend fun getSigners(id: String): SignerStatusResponse
    suspend fun broadcastRaw(id: String, req: BroadcastRawTxRequest): BroadcastResponse
    suspend fun signTrezor(id: String, req: AddTrezorSignaturesRequest): PsbtDetailResponse
    suspend fun delete(id: String)
}

class PsbtClientImpl(private val cfg: AppConfig) : PsbtClient {
    private lateinit var client: HttpClient
    fun attach(http: HttpClient) { client = http }

    // Legacy metody pro kompatibilitu
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
    
    // Nové metody pro psbt-service
    override suspend fun create(req: CreatePsbtRequest): CreatePsbtResponse {
        requireAttached(this::client.isInitialized, "psbt")
        val resp = upstreamRequest("psbt") {
            client.post("${cfg.psbtBaseUrl}/psbt/create") {
                contentType(ContentType.Application.Json)
                setBody(req)
            }
        }.ensureSuccess("psbt")
        return resp.body()
    }
    
    override suspend fun getById(id: String): PsbtDetailResponse {
        requireAttached(this::client.isInitialized, "psbt")
        val resp = upstreamRequest("psbt") {
            client.get("${cfg.psbtBaseUrl}/psbt/$id")
        }.ensureSuccess("psbt")
        return resp.body()
    }
    
    override suspend fun getByWallet(walletId: String, status: String?): PsbtListResponse {
        requireAttached(this::client.isInitialized, "psbt")
        val resp = upstreamRequest("psbt") {
            client.get("${cfg.psbtBaseUrl}/psbt/wallet/$walletId") {
                status?.let { parameter("status", it) }
            }
        }.ensureSuccess("psbt")
        return resp.body()
    }
    
    override suspend fun addSignature(id: String, req: AddSignatureRequest): PsbtDetailResponse {
        requireAttached(this::client.isInitialized, "psbt")
        val resp = upstreamRequest("psbt") {
            client.post("${cfg.psbtBaseUrl}/psbt/$id/sign") {
                contentType(ContentType.Application.Json)
                setBody(req)
            }
        }.ensureSuccess("psbt")
        return resp.body()
    }
    
    override suspend fun combine(id: String, req: CombinePsbtsRequest): PsbtDetailResponse {
        requireAttached(this::client.isInitialized, "psbt")
        val resp = upstreamRequest("psbt") {
            client.post("${cfg.psbtBaseUrl}/psbt/$id/combine") {
                contentType(ContentType.Application.Json)
                setBody(req)
            }
        }.ensureSuccess("psbt")
        return resp.body()
    }
    
    override suspend fun finalize(id: String): FinalizeResponse {
        requireAttached(this::client.isInitialized, "psbt")
        val resp = upstreamRequest("psbt") {
            client.post("${cfg.psbtBaseUrl}/psbt/$id/finalize")
        }.ensureSuccess("psbt")
        return resp.body()
    }
    
    override suspend fun broadcast(id: String): BroadcastResponse {
        requireAttached(this::client.isInitialized, "psbt")
        val resp = upstreamRequest("psbt") {
            client.post("${cfg.psbtBaseUrl}/psbt/$id/broadcast")
        }.ensureSuccess("psbt")
        return resp.body()
    }

    override suspend fun getSigners(id: String): SignerStatusResponse {
        requireAttached(this::client.isInitialized, "psbt")
        val resp = upstreamRequest("psbt") {
            client.get("${cfg.psbtBaseUrl}/psbt/$id/signers")
        }.ensureSuccess("psbt")
        return resp.body()
    }
    
    override suspend fun broadcastRaw(id: String, req: BroadcastRawTxRequest): BroadcastResponse {
        requireAttached(this::client.isInitialized, "psbt")
        val resp = upstreamRequest("psbt") {
            client.post("${cfg.psbtBaseUrl}/psbt/$id/broadcast-raw") {
                contentType(ContentType.Application.Json)
                setBody(req)
            }
        }.ensureSuccess("psbt")
        return resp.body()
    }

    override suspend fun signTrezor(id: String, req: AddTrezorSignaturesRequest): PsbtDetailResponse {
        requireAttached(this::client.isInitialized, "psbt")
        val resp = upstreamRequest("psbt") {
            client.post("${cfg.psbtBaseUrl}/psbt/$id/sign-trezor") {
                contentType(ContentType.Application.Json)
                setBody(req)
            }
        }.ensureSuccess("psbt")
        return resp.body()
    }

    override suspend fun delete(id: String) {
        requireAttached(this::client.isInitialized, "psbt")
        upstreamRequest("psbt") {
            client.delete("${cfg.psbtBaseUrl}/psbt/$id")
        }.ensureSuccess("psbt")
    }
}
