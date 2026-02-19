package cz.majny.wallet.psbt.client

import cz.majny.wallet.psbt.api.AddressDto
import cz.majny.wallet.psbt.api.WalletDetailDto
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Klient pro komunikaci s wallet-registry.
 */
class RegistryClient(private val baseUrl: String) {
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
    
    /**
     * Získá detail peněženky včetně descriptorů a cosignerů.
     */
    suspend fun getWallet(walletId: String): WalletDetailDto {
        return client.get("$baseUrl/registry/wallets/$walletId").body()
    }
    
    /**
     * Získá change adresu pro peněženku.
     */
    suspend fun getChangeAddress(walletId: String, index: Int = 0): AddressDto {
        return client.get("$baseUrl/registry/wallets/$walletId/addresses") {
            parameter("type", "change")
            parameter("index", index)
        }.body()
    }
    
    /**
     * Získá receive adresu pro peněženku.
     */
    suspend fun getReceiveAddress(walletId: String, index: Int = 0): AddressDto {
        return client.get("$baseUrl/registry/wallets/$walletId/addresses") {
            parameter("type", "receive")
            parameter("index", index)
        }.body()
    }
}
