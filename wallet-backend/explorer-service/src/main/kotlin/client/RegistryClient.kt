package cz.majny.wallet.explorer.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("RegistryClient")

/**
 * HTTP client pro wallet-registry.
 * Získává seznam adres peněženky.
 */
class RegistryClient(
    private val baseUrl: String,
    private val client: HttpClient
) {

    /**
     * Vrátí všechny odvozené adresy peněženky.
     * Volitelně filtrováno podle typu (receive/change).
     */
    suspend fun getAddresses(walletId: String, type: String? = null): List<WalletAddress> {
        log.debug("Getting addresses for wallet: {} type: {}", walletId, type)
        val response: WalletAddressesResponse = client.get("$baseUrl/registry/wallets/$walletId/addresses") {
            type?.let { parameter("type", it) }
        }.body()
        return response.addresses
    }

}

// ============ DTOs (mirror wallet-registry responses) ============

@Serializable
data class WalletAddress(
    val walletId: String,
    val address: String,
    val index: Int,
    val type: String    // "receive" or "change"
)

@Serializable
data class WalletAddressesResponse(
    val walletId: String,
    val addresses: List<WalletAddress>
)
