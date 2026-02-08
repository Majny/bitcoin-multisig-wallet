package cz.majny.wallet.gateway.clients

import cz.majny.wallet.gateway.dto.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType

interface RegistryClient {
    suspend fun upsertDevice(req: UpsertDeviceRequest): UpsertDeviceResponse

    suspend fun createWallet(req: CreateWalletRequest)

    suspend fun getWallet(walletId: String): WalletDetail

    suspend fun attachMember(walletId: String, req: MemberAttach)

    suspend fun listWallets(deviceId: String): List<RegistryWalletSummary>

    suspend fun getWalletAddress(walletId: String, type: String = "receive", index: Int = 0): WalletAddressResponse

    suspend fun getWalletAddresses(walletId: String, type: String? = null): WalletAddressesResponse
}

class RegistryClientImpl(
    private val baseUrl: String
) : RegistryClient {

    private var http: HttpClient? = null

    fun attach(http: HttpClient) {
        this.http = http
    }

    private fun client(): HttpClient =
        requireNotNull(http) {
            "RegistryClientImpl is not attached. Call deps.attachHttpClients(application) first."
        }

    override suspend fun upsertDevice(req: UpsertDeviceRequest): UpsertDeviceResponse {
        return client().post("$baseUrl/registry/devices") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()
    }

    override suspend fun createWallet(req: CreateWalletRequest) {
        val resp = client().post("$baseUrl/registry/wallets") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }

        resp.bodyAsText()
    }

    override suspend fun getWallet(walletId: String): WalletDetail {
        return client().get("$baseUrl/registry/wallets/$walletId").body()
    }

    override suspend fun attachMember(walletId: String, req: MemberAttach) {
        val resp = client().post("$baseUrl/registry/wallets/$walletId/members/attach") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }
        resp.bodyAsText()
    }

    override suspend fun listWallets(deviceId: String): List<RegistryWalletSummary> {
        return client().get("$baseUrl/registry/wallets") {
            url { parameters.append("device_id", deviceId) }
        }.body()
    }

    override suspend fun getWalletAddress(walletId: String, type: String, index: Int): WalletAddressResponse {
        return client().get("$baseUrl/registry/wallets/$walletId/addresses") {
            url {
                parameters.append("type", type)
                parameters.append("index", index.toString())
            }
        }.body()
    }

    override suspend fun getWalletAddresses(walletId: String, type: String?): WalletAddressesResponse {
        return client().get("$baseUrl/registry/wallets/$walletId/addresses") {
            if (type != null) url { parameters.append("type", type) }
        }.body()
    }
}
