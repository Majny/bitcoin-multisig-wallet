package cz.majny.wallet.gateway.clients

import cz.majny.wallet.gateway.config.AppConfig
import cz.majny.wallet.gateway.dto.WalletSummarySerializable
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*

interface RegistryClient {
    suspend fun listWallets(deviceId: String): List<WalletSummarySerializable>
}

class RegistryClientImpl(private val cfg: AppConfig) : RegistryClient {
    private lateinit var client: HttpClient
    fun attach(http: HttpClient) { client = http }

    override suspend fun listWallets(deviceId: String): List<WalletSummarySerializable> {
        requireAttached(this::client.isInitialized, "registry")

        val resp = upstreamRequest("registry") {
            client.get("${cfg.registryBaseUrl}/wallets") {
                parameter("device_id", deviceId)
            }
        }.ensureSuccess("registry")

        return resp.body()
    }
}
