package cz.majny.wallet.psbt.client

import cz.majny.wallet.psbt.api.AddressDto
import cz.majny.wallet.psbt.api.WalletDetailDto
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/* HTTP client for communicating with wallet-registry. */
class RegistryClient(private val baseUrl: String) {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }

    /* Fetches wallet detail including descriptors and cosigners. */
    suspend fun getWallet(walletId: String): WalletDetailDto {
        return client.get("$baseUrl/registry/wallets/$walletId").body()
    }

    /* Fetches all addresses for a wallet (receive + change). Used for UTXO scanning. */
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
