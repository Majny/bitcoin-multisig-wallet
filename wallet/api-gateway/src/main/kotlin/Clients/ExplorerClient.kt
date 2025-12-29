package cz.majny.wallet.gateway.clients

import cz.majny.wallet.gateway.config.AppConfig
import cz.majny.wallet.gateway.dto.*
import cz.majny.wallet.gateway.plugins.UpstreamException
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.client.statement.*


interface ExplorerClient {
    suspend fun getUtxos(walletId: String): List<UtxoDto>
}

class ExplorerClientImpl(private val cfg: AppConfig) : ExplorerClient {
    private lateinit var client: HttpClient
    fun attach(http: HttpClient) { client = http }

    override suspend fun getUtxos(walletId: String): List<UtxoDto> {
        val resp = client.get("${cfg.explorerBaseUrl}/wallets/$walletId/utxos")
        if (!resp.status.isSuccess()) throw UpstreamException("explorer", resp.status, resp.bodyAsText())
        return resp.body()
    }
}
