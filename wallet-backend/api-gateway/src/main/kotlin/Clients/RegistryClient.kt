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
import io.ktor.http.isSuccess

interface RegistryClient {
    suspend fun createWallet(req: CreateWalletRequest): WalletDetail

    suspend fun getWallet(walletId: String): WalletDetail

    suspend fun attachMember(walletId: String, req: MemberAttach)

    suspend fun listWallets(deviceId: String): List<RegistryWalletSummary>

    suspend fun getWalletAddress(walletId: String, type: String = "receive", index: Int = 0): WalletAddressResponse

    suspend fun importWallet(req: ImportWalletGatewayRequest): ImportWalletGatewayResponse

    suspend fun deriveAddresses(descriptor: String, network: String, count: Int = 5): List<String>
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

    override suspend fun createWallet(req: CreateWalletRequest): WalletDetail {
        return client().post("$baseUrl/registry/wallets") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()
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

    override suspend fun importWallet(req: ImportWalletGatewayRequest): ImportWalletGatewayResponse {
        return try {
            val resp = client().post("$baseUrl/registry/wallets/import") {
                contentType(ContentType.Application.Json)
                setBody(req)
            }
            if (resp.status.isSuccess()) {
                resp.body()
            } else {
                val errorBody = resp.bodyAsText()
                ImportWalletGatewayResponse(success = false, error = errorBody)
            }
        } catch (e: Exception) {
            ImportWalletGatewayResponse(success = false, error = e.message ?: "import failed")
        }
    }

    override suspend fun deriveAddresses(descriptor: String, network: String, count: Int): List<String> {
        val resp: DeriveAddressesResponse = client().post("$baseUrl/registry/derive-addresses") {
            contentType(ContentType.Application.Json)
            setBody(DeriveAddressesRequest(descriptor = descriptor, network = network, count = count))
        }.body()
        return resp.addresses
    }
}
