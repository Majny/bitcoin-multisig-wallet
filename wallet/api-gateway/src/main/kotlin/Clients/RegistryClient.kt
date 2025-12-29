package cz.majny.wallet.gateway.clients

import cz.majny.wallet.gateway.config.AppConfig
import cz.majny.wallet.gateway.dto.*
import cz.majny.wallet.gateway.plugins.UpstreamException
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.client.statement.*


interface RegistryClient {
    suspend fun listWallets(deviceId: String): List<WalletSummarySerializable>
}

class RegistryClientImpl(private val cfg: AppConfig) : RegistryClient {
    private lateinit var client: HttpClient
    fun attach(http: HttpClient) { client = http }

    override suspend fun listWallets(deviceId: String): List<WalletSummarySerializable> {
        val resp = client.get("${cfg.registryBaseUrl}/wallets") {
            parameter("device_id", deviceId)
        }
        if (!resp.status.isSuccess()) throw UpstreamException("registry", resp.status, resp.bodyAsText())
        return resp.body()
    }
}
