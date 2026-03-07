package cz.majny.wallet.psbt.client

import cz.majny.wallet.psbt.api.AddressDto
import cz.majny.wallet.psbt.api.WalletDetailDto
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
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
     * Získá všechny adresy peněženky (receive + change).
     * Pokud type je specifikován, vrátí jen daný typ.
     */
    suspend fun getAllAddresses(walletId: String, type: String? = null): List<AddressDto> {
        val response: WalletAddressesResponse = client.get("$baseUrl/registry/wallets/$walletId/addresses") {
            if (type != null) parameter("type", type)
        }.body()
        return response.addresses
    }
}

@Serializable
data class WalletAddressesResponse(
    val walletId: String,
    val addresses: List<AddressDto>
)
