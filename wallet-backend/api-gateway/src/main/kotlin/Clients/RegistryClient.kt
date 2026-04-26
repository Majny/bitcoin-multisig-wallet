package cz.majny.wallet.gateway.clients

import cz.majny.wallet.gateway.dto.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/* HTTP client facade for wallet-registry endpoints used by gateway routes. */
interface RegistryClient {
    suspend fun createWallet(req: CreateWalletRequest): WalletDetail
    suspend fun getWallet(walletId: String): WalletDetail
    suspend fun attachMember(walletId: String, req: MemberAttach)
    suspend fun listWallets(deviceId: String): List<RegistryWalletSummary>
    suspend fun getWalletAddress(walletId: String, type: String = "receive", index: Int = 0): WalletAddressResponse
    suspend fun importWallet(req: ImportWalletGatewayRequest): ImportWalletGatewayResponse
    suspend fun deriveAddresses(descriptor: String, network: String, count: Int = 5): List<String>
    suspend fun updateCosignerLabel(walletId: String, cosignerIdx: Int, label: String, deviceId: String)
    suspend fun getCosignerLabels(walletId: String, deviceId: String): Map<Int, String>
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

    /* POST /registry/wallets — creates a wallet record (singlesig or multisig). */
    override suspend fun createWallet(req: CreateWalletRequest): WalletDetail {
        return client().post("$baseUrl/registry/wallets") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()
    }

    /* GET /registry/wallets/{walletId} — full wallet detail including cosigners. */
    override suspend fun getWallet(walletId: String): WalletDetail {
        return client().get("$baseUrl/registry/wallets/$walletId").body()
    }

    /* POST /registry/wallets/{walletId}/members/attach — adds a device as a member. */
    override suspend fun attachMember(walletId: String, req: MemberAttach) {
        val resp = client().post("$baseUrl/registry/wallets/$walletId/members/attach") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }
        resp.bodyAsText()
    }

    /* GET /registry/wallets?device_id=... — wallets where the device is a member. */
    override suspend fun listWallets(deviceId: String): List<RegistryWalletSummary> {
        return client().get("$baseUrl/registry/wallets") {
            url { parameters.append("device_id", deviceId) }
        }.body()
    }

    /* GET /registry/wallets/{walletId}/addresses — single receive/change address by index. */
    override suspend fun getWalletAddress(walletId: String, type: String, index: Int): WalletAddressResponse {
        return client().get("$baseUrl/registry/wallets/$walletId/addresses") {
            url {
                parameters.append("type", type)
                parameters.append("index", index.toString())
            }
        }.body()
    }

    /* POST /registry/wallets/import — imports a multisig wallet from a descriptor.
     * Translates non-2xx responses into an ImportWalletGatewayResponse(success=false)
     * so route handlers can surface the upstream error message verbatim to the UI. */
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

    /* POST /registry/derive-addresses — stateless derivation from a descriptor,
     * used during account-discovery without creating a wallet yet. */
    override suspend fun deriveAddresses(descriptor: String, network: String, count: Int): List<String> {
        val resp: DeriveAddressesResponse = client().post("$baseUrl/registry/derive-addresses") {
            contentType(ContentType.Application.Json)
            setBody(DeriveAddressesRequest(descriptor = descriptor, network = network, count = count))
        }.body()
        return resp.addresses
    }

    /* PUT /registry/wallets/{walletId}/cosigners/{idx}/label — upserts a
     * per-device cosigner label. Labels are scoped to the caller's device so
     * personal notes don't leak between multisig members. */
    override suspend fun updateCosignerLabel(
        walletId: String,
        cosignerIdx: Int,
        label: String,
        deviceId: String
    ) {
        client().put("$baseUrl/registry/wallets/$walletId/cosigners/$cosignerIdx/label") {
            contentType(ContentType.Application.Json)
            setBody(UpdateCosignerLabelRegistryRequest(label = label, deviceId = deviceId))
        }
    }

    /* GET /registry/wallets/{walletId}/cosigner-labels?device_id=... — fetches
     * the caller's own labels to layer onto generic signer responses. */
    override suspend fun getCosignerLabels(walletId: String, deviceId: String): Map<Int, String> {
        val resp: CosignerLabelsResponse = client().get("$baseUrl/registry/wallets/$walletId/cosigner-labels") {
            url { parameters.append("device_id", deviceId) }
        }.body()
        return resp.labels.associate { it.idx to it.label }
    }
}
