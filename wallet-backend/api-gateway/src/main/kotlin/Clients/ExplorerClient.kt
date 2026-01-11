package cz.majny.wallet.gateway.clients

import cz.majny.wallet.gateway.config.AppConfig
import cz.majny.wallet.gateway.dto.UtxoDto
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*

interface ExplorerClient {
    suspend fun getUtxos(walletId: String): List<UtxoDto>
}

class ExplorerClientImpl(private val cfg: AppConfig) : ExplorerClient {
    private lateinit var client: HttpClient
    fun attach(http: HttpClient) { client = http }

    override suspend fun getUtxos(walletId: String): List<UtxoDto> {
        requireAttached(this::client.isInitialized, "explorer")

        val resp = upstreamRequest("explorer") {
            client.get("${cfg.explorerBaseUrl}/wallets/$walletId/utxos")
        }.ensureSuccess("explorer")

        return resp.body()
    }
}
